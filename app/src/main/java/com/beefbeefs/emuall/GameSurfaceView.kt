package com.beefbeefs.emuall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Process
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

class GameSurfaceView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {
    private val gameRenderer = GameRenderer()
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val speed = AtomicInteger(1)
    private val pendingAction = AtomicInteger(ACTION_NONE)
    private val pendingSlot = AtomicInteger(1)
    private var emulationThread: Thread? = null
    private var inputMask = 0
    private var hardwareRendering = false
    private val hardwareStarted = AtomicBoolean(false)
    private val hardwareStartAttempted = AtomicBoolean(false)
    private var hardwareSession: HardwareSession? = null
    private var hardwareAudioRunning: AtomicBoolean? = null
    private var hardwareAudioThread: Thread? = null
    private var firstHardwarePresentation = true
    private var hardwareFramePeriodNanos = 16_666_667L
    private var hardwareNextFrameNanos = 0L
    private var hardwareMinimumYieldMillis = 0L
    private var statusCallback: ((String) -> Unit)? = null
    @Volatile private var glSurfaceWidth = 0
    @Volatile private var glSurfaceHeight = 0
    private val requestNextHardwareFrame = Runnable {
        if (running.get() && hardwareRendering && !paused.get()) requestRender()
    }

    private data class HardwareSession(
        val corePath: String,
        val romPath: String,
        val savePath: String,
        val systemDirectory: String,
        val coreName: String,
        val videoBackend: VideoBackend,
    )

    init {
        // The software texture path works on GLES3 as well, while the first
        // hardware core (Mupen64Plus-Next GLES3) requires an ES 3 context.
        setEGLContextClientVersion(3)
        setEGLContextFactory(HighestGlesContextFactory())
        // Hardware libretro cores are allowed to request depth/stencil
        // buffers in SET_HW_RENDER.  Ask Android for a config that satisfies
        // the N64 renderer up front; the same config is harmless for the
        // software texture path.
        setEGLConfigChooser(8, 8, 8, 8, 24, 8)
        preserveEGLContextOnPause = true
        setRenderer(gameRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun start(
        corePath: String,
        romPath: String,
        savePath: String,
        systemDirectory: String,
        coreName: String,
        videoBackend: VideoBackend = VideoBackend.OPENGL_ES,
        hardwareRendering: Boolean = false,
        onStatus: (String) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        this.hardwareRendering = hardwareRendering
        this.statusCallback = onStatus
        if (hardwareRendering) {
            hardwareSession = HardwareSession(corePath, romPath, savePath, systemDirectory, coreName, videoBackend)
            hardwareStartAttempted.set(false)
            hardwareStarted.set(false)
            firstHardwarePresentation = true
            hardwareNextFrameNanos = 0L
            hardwareMinimumYieldMillis = 0L
            // A dirty renderer produces exactly one Android presentation for
            // each requested core frame. Continuous mode also drew between
            // core frames (often at 120 Hz), which could sample Dolphin's live
            // framebuffer while its dual-core renderer was updating it and
            // starved Android input while Play! was busy.
            renderMode = RENDERMODE_WHEN_DIRTY
            requestRender()
            return
        }
        emulationThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            if (!NativeCoreBridge.start(corePath, romPath, savePath, systemDirectory, false, context.assets)) {
                running.set(false)
                post { onStatus(NativeCoreBridge.lastError()) }
                return@Thread
            }
            val sampleRate = NativeCoreBridge.sampleRate().coerceAtLeast(8000)
            val coreFps = NativeCoreBridge.framesPerSecond().coerceAtLeast(1.0)
            val audio = createAudioTrack(sampleRate)
            val audioRunning = AtomicBoolean(audio != null)
            val audioThread = audio?.let { track ->
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val samples = ShortArray(4096)
                    track.play()
                    while (audioRunning.get() && running.get()) {
                        val count = NativeCoreBridge.drainAudio(samples)
                        if (count <= 0) {
                            Thread.sleep(2)
                        } else if (!paused.get() && speed.get() == 1) {
                            var offset = 0
                            while (offset < count && audioRunning.get() && running.get()) {
                                val written = track.write(samples, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                                if (written <= 0) break
                                offset += written
                            }
                        }
                        // Paused and fast-forward audio is intentionally drained and discarded.
                    }
                    track.pause()
                    track.flush()
                    track.release()
                }, "EmuAll-Audio").also { it.start() }
            }
            post { onStatus("$coreName · ${videoBackend.label} · Starting · target ${"%.1f".format(coreFps)} FPS") }
            val baseFrameNanos = (1_000_000_000.0 / coreFps).toLong()
            var deadline = System.nanoTime()
            var measurementStart = deadline
            var measuredFrames = 0
            var lastSave = deadline
            var transientStatusUntil = 0L
            try {
                while (running.get()) {
                    val action = pendingAction.getAndSet(ACTION_NONE)
                    if (action != ACTION_NONE) {
                        val slot = pendingSlot.get().coerceIn(1, 3)
                        val statePath = statePath(savePath, slot)
                        val message = when (action) {
                            ACTION_RESET -> {
                                NativeCoreBridge.reset()
                                NativeCoreBridge.runFrame()
                                requestRender()
                                "Game reset"
                            }
                            ACTION_QUICK_SAVE -> if (NativeCoreBridge.quickSave(statePath)) {
                                captureStateThumbnail("$statePath.png")
                                "Saved Slot $slot"
                            } else NativeCoreBridge.lastError()
                            ACTION_QUICK_LOAD -> if (NativeCoreBridge.quickLoad(statePath)) {
                                NativeCoreBridge.runFrame()
                                requestRender()
                                "Loaded Slot $slot"
                            } else NativeCoreBridge.lastError()
                            else -> ""
                        }
                        transientStatusUntil = System.nanoTime() + 2_000_000_000L
                        post { onStatus(message) }
                    }
                    if (paused.get()) {
                        Thread.sleep(12)
                        deadline = System.nanoTime()
                        measurementStart = deadline
                        measuredFrames = 0
                        continue
                    }
                    if (!NativeCoreBridge.runFrame()) {
                        running.set(false)
                        post { onStatus(NativeCoreBridge.lastError().ifBlank { "The native core stopped unexpectedly" }) }
                        break
                    }
                    requestRender()
                    measuredFrames++
                    val now = System.nanoTime()
                    val measurementNanos = now - measurementStart
                    if (measurementNanos >= 1_000_000_000L) {
                        val actualFps = measuredFrames * 1_000_000_000.0 / measurementNanos
                        val mode = if (speed.get() > 1) " · ${speed.get()}×" else ""
                        if (now >= transientStatusUntil) {
                            post { onStatus("$coreName · ${videoBackend.label} · ${"%.1f".format(actualFps)} FPS$mode") }
                        }
                        measurementStart = now
                        measuredFrames = 0
                    }
                    if (now - lastSave > 10_000_000_000L) {
                        NativeCoreBridge.saveBattery()
                        lastSave = now
                    }
                    val frameNanos = baseFrameNanos / speed.get().coerceAtLeast(1)
                    deadline += frameNanos
                    val remaining = deadline - System.nanoTime()
                    if (remaining > 0) {
                        Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                    } else if (remaining < -baseFrameNanos * 4) {
                        deadline = System.nanoTime()
                    }
                }
            } finally {
                audioRunning.set(false)
                audioThread?.join(1000)
                NativeCoreBridge.stop()
            }
        }, "EmuAll-$coreName").also { it.start() }
    }

    private fun startHardwareSessionIfNeeded() {
        val session = hardwareSession ?: return
        if (!hardwareStartAttempted.compareAndSet(false, true)) return
        val isPlay = session.coreName.contains("Play!", ignoreCase = true) ||
            session.corePath.contains("libplay_", ignoreCase = true)
        if (isPlay) {
            // Play! creates its continuously-running PS2 worker threads while
            // retro_init executes below. Let them inherit background priority
            // so the Android main thread can always dispatch touches instead
            // of tripping the five-second application-not-responding limit.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            // Even when Play!'s first frames take seconds, require the next
            // frame request to pass through Android's main queue. This gives
            // pending touches and toolbar actions an input-dispatch turn and
            // prevents consecutive retro_run calls from monopolizing the app.
            hardwareMinimumYieldMillis = 8L
        }
        if (!NativeCoreBridge.start(session.corePath, session.romPath, session.savePath, session.systemDirectory, true, context.assets)) {
            running.set(false)
            statusCallback?.invoke(NativeCoreBridge.lastError())
            return
        }
        if (isPlay) NativeCoreBridge.diagnosticMarker("Play! workers use background priority to preserve Android input responsiveness")
        val coreFps = NativeCoreBridge.framesPerSecond().takeIf { it.isFinite() && it >= 1.0 } ?: 60.0
        hardwareFramePeriodNanos = (1_000_000_000.0 / coreFps).toLong().coerceAtLeast(1L)
        hardwareNextFrameNanos = System.nanoTime()
        hardwareStarted.set(true)
        startHardwareAudio(NativeCoreBridge.sampleRate().coerceAtLeast(8000))
        NativeCoreBridge.diagnosticMarker("Hardware pacing target=${"%.4f".format(coreFps)} FPS")
        statusCallback?.invoke("${session.coreName} · ${session.videoBackend.label} hardware context · Starting · target ${"%.1f".format(coreFps)} FPS")
    }

    private fun startHardwareAudio(sampleRate: Int) {
        val audio = createAudioTrack(sampleRate) ?: return
        val audioRunning = AtomicBoolean(true)
        hardwareAudioRunning = audioRunning
        hardwareAudioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val samples = ShortArray(4096)
            audio.play()
            try {
                while (audioRunning.get() && running.get()) {
                    val count = NativeCoreBridge.drainAudio(samples)
                    if (count <= 0) Thread.sleep(2)
                    else if (!paused.get() && speed.get() == 1) {
                        var offset = 0
                        while (offset < count && audioRunning.get() && running.get()) {
                            val written = audio.write(samples, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                            if (written <= 0) break
                            offset += written
                        }
                    }
                }
            } finally {
                audio.pause(); audio.flush(); audio.release()
            }
        }, "EmuAll-HardwareAudio").also { it.start() }
    }

    /** Runs one hardware frame on the GLSurfaceView thread, where the GL context is current. */
    private fun renderHardwareFrame() {
        val width = glSurfaceWidth
        val height = glSurfaceHeight
        // GLSurfaceView can schedule its first draw before the host has a
        // measured size. Starting a core in that window permanently gives
        // some renderers the tiny bootstrap viewport (the PSP symptom was a
        // 310x176 image in a portrait surface). Wait for a real surface size.
        if (width <= 1 || height <= 1) {
            scheduleNextHardwareFrame(16L)
            return
        }
        // Hardware cores render into a frontend-owned GLES framebuffer. This
        // keeps their fixed 480p/PSP-sized backbuffer independent from the
        // phone's portrait dimensions and lets us scale it cleanly below.
        gameRenderer.ensureHardwareTarget()
        // A few hardware cores (especially Play!) can spend several seconds
        // producing their first frame. Starting before Android delivers the
        // Activity's initial focus event can overlap the five-second input
        // dispatch deadline and cause an ANR even though retro_run succeeds.
        if (!hardwareStarted.get() && !hasWindowFocus()) {
            scheduleNextHardwareFrame(16L)
            return
        }
        val aspect = NativeCoreBridge.videoAspectRatio().takeIf { it.isFinite() && it > 0.01f }?.toDouble()
        val viewport = fitViewport(width, height, aspect)
        if (hardwareStarted.get() && paused.get()) {
            // Preserve and present the last completed texture while paused.
            // Clearing the core FBO first made Pause (and control-edit mode)
            // display a black frame on hardware-rendered systems.
            gameRenderer.presentHardwareFrame(viewport)
            return
        }
        startHardwareSessionIfNeeded()
        if (!hardwareStarted.get()) return

        // Advance the core only at its advertised rate. RENDERMODE_WHEN_DIRTY
        // means early requests are rescheduled instead of swapping the live
        // hardware target a second time between emulated frames.
        val frameStartNanos = System.nanoTime()
        if (!firstHardwarePresentation && frameStartNanos < hardwareNextFrameNanos) {
            gameRenderer.presentHardwareFrame(viewport)
            scheduleNextHardwareFrame(nanosToDelayMillis(hardwareNextFrameNanos - frameStartNanos))
            return
        }

        val session = hardwareSession ?: return
        val action = pendingAction.getAndSet(ACTION_NONE)
        var saveThumbnail: String? = null
        if (action != ACTION_NONE) {
            val slot = pendingSlot.get().coerceIn(1, 3)
            val statePath = statePath(session.savePath, slot)
            val message = when (action) {
                ACTION_RESET -> { NativeCoreBridge.reset(); "Game reset" }
                ACTION_QUICK_SAVE -> if (NativeCoreBridge.quickSave(statePath)) {
                    saveThumbnail = "$statePath.png"
                    "Saved Slot $slot"
                } else NativeCoreBridge.lastError()
                ACTION_QUICK_LOAD -> if (NativeCoreBridge.quickLoad(statePath)) "Loaded Slot $slot" else NativeCoreBridge.lastError()
                else -> ""
            }
            statusCallback?.invoke(message)
        }

        // Hardware cores render into the frontend-owned target. Keep that
        // output aspect-correct and letterboxed rather than stretching it to
        // the phone's current orientation. The ratio comes from the
        // core's libretro AV geometry (PSP 16:9, N64/GC/PS2 4:3 by default,
        // and any core-specific runtime geometry when available).
        val updatedAspect = NativeCoreBridge.videoAspectRatio().takeIf { it.isFinite() && it > 0.01f }?.toDouble()
        val updatedViewport = fitViewport(width, height, updatedAspect)
        if (gameRenderer.hasHardwareTarget()) {
            gameRenderer.bindHardwareTarget()
            GLES20.glViewport(0, 0, gameRenderer.hardwareWidth(), gameRenderer.hardwareHeight())
        } else {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
        }
        // The framebuffer belongs to the core for the duration of the
        // session. Do not clear it here: Play!, Dolphin and other renderers
        // may preserve or update only part of their output between frames.
        if (!NativeCoreBridge.runFrame()) {
            running.set(false)
            statusCallback?.invoke(NativeCoreBridge.lastError().ifBlank { "The native core stopped unexpectedly" })
            return
        }
        if (firstHardwarePresentation) NativeCoreBridge.diagnosticMarker("Presenting first hardware frame")
        gameRenderer.presentHardwareFrame(updatedViewport)
        if (firstHardwarePresentation) {
            NativeCoreBridge.diagnosticMarker("First hardware frame presented")
            firstHardwarePresentation = false
        }
        hardwareNextFrameNanos += hardwareFramePeriodNanos
        val frameCompletedNanos = System.nanoTime()
        if (hardwareNextFrameNanos < frameCompletedNanos - hardwareFramePeriodNanos * 2) {
            hardwareNextFrameNanos = frameCompletedNanos
        }
        saveThumbnail?.let { gameRenderer.captureHardwareThumbnail(it) }
        val remainingNanos = (hardwareNextFrameNanos - System.nanoTime()).coerceAtLeast(0L)
        scheduleNextHardwareFrame(maxOf(hardwareMinimumYieldMillis, nanosToDelayMillis(remainingNanos)))
    }

    private fun nanosToDelayMillis(nanos: Long): Long =
        ((nanos.coerceAtLeast(0L) + 999_999L) / 1_000_000L).coerceAtLeast(1L)

    private fun scheduleNextHardwareFrame(delayMillis: Long) {
        removeCallbacks(requestNextHardwareFrame)
        postDelayed(requestNextHardwareFrame, delayMillis.coerceAtLeast(1L))
    }

    /** Re-lays out the controls without pausing or reparenting the EGL view. */
    fun onLayoutChanged() {
        requestRender()
        queueEvent {
            val width = glSurfaceWidth
            val height = glSurfaceHeight
            if (width > 1 && height > 1) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, width, height)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
            requestRender()
        }
    }

    private fun fitViewport(width: Int, height: Int, aspect: Double?): IntArray {
        if (aspect == null) return intArrayOf(0, 0, width, height)
        val surfaceAspect = width.toDouble() / height.toDouble()
        return if (surfaceAspect > aspect) {
            val viewportHeight = height
            val viewportWidth = (viewportHeight * aspect).toInt().coerceAtLeast(1)
            intArrayOf((width - viewportWidth) / 2, 0, viewportWidth, viewportHeight)
        } else {
            val viewportWidth = width
            val viewportHeight = (viewportWidth / aspect).toInt().coerceAtLeast(1)
            intArrayOf(0, (height - viewportHeight) / 2, viewportWidth, viewportHeight)
        }
    }

    private fun stopHardwareSession() {
        removeCallbacks(requestNextHardwareFrame)
        hardwareAudioRunning?.set(false)
        hardwareAudioThread?.join(1000)
        hardwareAudioThread = null
        hardwareAudioRunning = null
        if (hardwareStarted.compareAndSet(true, false)) NativeCoreBridge.stop()
        hardwareNextFrameNanos = 0L
        hardwareMinimumYieldMillis = 0L
        hardwareSession = null
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack? = try {
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(minimum, sampleRate / 10 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state == AudioTrack.STATE_INITIALIZED) track else {
            track.release()
            null
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun captureStateThumbnail(path: String): Boolean = runCatching {
        val width = NativeCoreBridge.frameWidth()
        val height = NativeCoreBridge.frameHeight()
        val pixelFormat = NativeCoreBridge.pixelFormat()
        if (width <= 0 || height <= 0 || pixelFormat !in setOf(1, 2)) return@runCatching false
        val bytesPerPixel = if (pixelFormat == 1) 4 else 2
        val pixels = ByteBuffer.allocateDirect(width * height * bytesPerPixel).order(ByteOrder.nativeOrder())
        if (NativeCoreBridge.copyFrame(pixels) <= 0) return@runCatching false
        pixels.position(0)
        val bitmap = Bitmap.createBitmap(width, height, if (pixelFormat == 1) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
        bitmap.copyPixelsFromBuffer(pixels)
        FileOutputStream(File(path)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        true
    }.getOrDefault(false)

    fun setPaused(value: Boolean) {
        paused.set(value)
        removeCallbacks(requestNextHardwareFrame)
        if (hardwareRendering) requestRender()
    }
    fun isPaused() = paused.get()
    fun toggleFastForward(): Boolean { val enabled = speed.get() == 1; speed.set(if (enabled) 3 else 1); return enabled }
    fun resetGame() {
        pendingAction.set(ACTION_RESET)
        if (hardwareRendering) requestRender()
    }
    fun quickSave(slot: Int = 1) {
        pendingSlot.set(slot.coerceIn(1, 3))
        pendingAction.set(ACTION_QUICK_SAVE)
        if (hardwareRendering) requestRender()
    }
    fun quickLoad(slot: Int = 1) {
        pendingSlot.set(slot.coerceIn(1, 3))
        pendingAction.set(ACTION_QUICK_LOAD)
        if (hardwareRendering) requestRender()
    }
    fun setButton(id: Int, down: Boolean) = synchronized(this) {
        inputMask = if (down) inputMask or (1 shl id) else inputMask and (1 shl id).inv()
        NativeCoreBridge.setInputMask(inputMask)
    }
    /** Sends a normalized virtual-stick value to libretro in its [-32767,32767] range. */
    fun setAnalog(stick: Int, x: Float, y: Float) {
        if (stick !in 0..1) return
        val scale = 32767f
        NativeCoreBridge.setAnalog(stick, (x.coerceIn(-1f, 1f) * scale).toInt(), (y.coerceIn(-1f, 1f) * scale).toInt())
    }
    fun stop() {
        running.set(false)
        NativeCoreBridge.setAnalog(0, 0, 0)
        NativeCoreBridge.setAnalog(1, 0, 0)
        synchronized(this) {
            inputMask = 0
            NativeCoreBridge.setInputMask(0)
        }
        if (hardwareRendering) {
            val stopped = CountDownLatch(1)
            queueEvent {
                stopHardwareSession()
                stopped.countDown()
            }
            requestRender()
            if (!stopped.await(2, TimeUnit.SECONDS)) {
                // Surface teardown can race Activity destruction. Ensure the
                // native session is not left running even if the GL queue stops.
                if (hardwareStarted.compareAndSet(true, false)) NativeCoreBridge.stop()
            }
            hardwareRendering = false
        } else {
            emulationThread?.join(2000)
            emulationThread = null
        }
    }

    private fun statePath(savePath: String, slot: Int) = if (slot == 1) "$savePath.quick.state" else "$savePath.state.$slot"

    private inner class GameRenderer : Renderer {
        private val frame = ByteBuffer.allocateDirect(1024 * 1024 * 4).order(ByteOrder.nativeOrder())
        private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)); position(0)
        }
        private var program = 0
        private var texture = 0
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var textureWidth = 0
        private var textureHeight = 0
        private var textureFormat = 0
        private var hardwareFramebuffer = 0
        private var hardwareTexture = 0
        private var hardwareDepthStencil = 0
        private var hardwareTargetWidth = 640
        private var hardwareTargetHeight = 480
        private var positionLocation = -1
        private var textureCoordinateLocation = -1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val majorValue = IntArray(1)
            val minorValue = IntArray(1)
            GLES30.glGetIntegerv(GL_MAJOR_VERSION, majorValue, 0)
            GLES30.glGetIntegerv(GL_MINOR_VERSION, minorValue, 0)
            var major = majorValue[0]
            var minor = minorValue[0]
            if (major < 2) {
                val parsed = Regex("OpenGL ES (\\d+)\\.(\\d+)")
                    .find(GLES20.glGetString(GLES20.GL_VERSION).orEmpty())
                major = parsed?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 3
                minor = parsed?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            }
            NativeCoreBridge.setGraphicsContextVersion(major, minor)
            textureWidth = 0
            textureHeight = 0
            textureFormat = 0
            hardwareFramebuffer = 0
            hardwareTexture = 0
            hardwareDepthStencil = 0
            NativeCoreBridge.setHardwareFramebuffer(0)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 p;attribute vec2 t;varying vec2 uv;void main(){gl_Position=vec4(p,0.,1.);uv=t;}")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float;uniform sampler2D s;varying vec2 uv;void main(){gl_FragColor=texture2D(s,uv);}")
            program = GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vertex); GLES20.glAttachShader(it, fragment); GLES20.glLinkProgram(it) }
            positionLocation = GLES20.glGetAttribLocation(program, "p")
            textureCoordinateLocation = GLES20.glGetAttribLocation(program, "t")
            val textures = IntArray(1); GLES20.glGenTextures(1, textures, 0); texture = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            if (hardwareRendering && hardwareSession != null) {
                // A rotation may recreate the EGL surface without recreating
                // the Activity. Tell the core that its old GL objects are no
                // longer valid before rebuilding our target FBO, then reset
                // the same running core against the new context.
                if (hardwareStarted.get()) NativeCoreBridge.hardwareContextDestroy()
                ensureHardwareTarget()
                if (hardwareStarted.get()) NativeCoreBridge.hardwareContextReset()
            }
            post { requestRender() }
        }
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width; surfaceHeight = height
            glSurfaceWidth = width; glSurfaceHeight = height
            requestRender()
        }

        fun ensureHardwareTarget() {
            if (!hardwareRendering || hardwareFramebuffer != 0) return
            val coreName = hardwareSession?.coreName?.lowercase().orEmpty()
            if (coreName.contains("ppsspp")) {
                hardwareTargetWidth = 480
                hardwareTargetHeight = 272
            } else if (coreName.contains("flycast")) {
                // Flycast's default 480-line 16:9 output is 853x480. A
                // 640x480 target clips the core's viewport and some builds
                // attempt to attach intermediate buffers at the larger size.
                hardwareTargetWidth = 853
                hardwareTargetHeight = 480
            } else if (coreName.contains("dolphin")) {
                // GameCube's normal EFB output is 640x528 and may reach the
                // core-advertised 640x576 maximum. A 480-line attachment
                // clipped every frame before it reached the presenter.
                hardwareTargetWidth = 640
                hardwareTargetHeight = 576
            } else {
                // N64, GameCube/Wii and Play! advertise a 4:3
                // 640x480-style backbuffer. The core can still change its
                // runtime geometry; the final presentation uses that ratio.
                hardwareTargetWidth = 640
                hardwareTargetHeight = 480
            }
            val framebufferIds = IntArray(1)
            val textureIds = IntArray(1)
            val renderbufferIds = IntArray(1)
            GLES30.glGenFramebuffers(1, framebufferIds, 0)
            GLES30.glGenTextures(1, textureIds, 0)
            GLES30.glGenRenderbuffers(1, renderbufferIds, 0)
            hardwareFramebuffer = framebufferIds[0]
            hardwareTexture = textureIds[0]
            hardwareDepthStencil = renderbufferIds[0]

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hardwareTexture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, hardwareTargetWidth, hardwareTargetHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)

            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, hardwareDepthStencil)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH24_STENCIL8, hardwareTargetWidth, hardwareTargetHeight)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, hardwareFramebuffer)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, hardwareTexture, 0)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, hardwareDepthStencil)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_STENCIL_ATTACHMENT, GLES30.GL_RENDERBUFFER, hardwareDepthStencil)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glDeleteFramebuffers(1, framebufferIds, 0)
                GLES30.glDeleteTextures(1, textureIds, 0)
                GLES30.glDeleteRenderbuffers(1, renderbufferIds, 0)
                hardwareFramebuffer = 0
                hardwareTexture = 0
                hardwareDepthStencil = 0
                NativeCoreBridge.setHardwareFramebuffer(0)
                return
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            NativeCoreBridge.setHardwareFramebuffer(hardwareFramebuffer)
        }

        fun bindHardwareTarget() {
            if (hardwareFramebuffer != 0) GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, hardwareFramebuffer)
        }

        fun hasHardwareTarget() = hardwareFramebuffer != 0 && hardwareTexture != 0
        fun hardwareWidth() = hardwareTargetWidth
        fun hardwareHeight() = hardwareTargetHeight

        fun presentHardwareFrame(viewport: IntArray) {
            if (hardwareFramebuffer == 0) return
            // Present with the GLES framebuffer blitter. Unlike drawing a
            // textured quad, this does not replace the core's program, VAO,
            // buffers, texture unit, viewport, blend/depth state, or masks.
            // Several Android cores retain those objects between retro_run
            // calls, so even a mostly-restored frontend draw can corrupt the
            // next frame or trigger an Adreno driver crash.
            val previousReadFramebuffer = IntArray(1)
            val previousDrawFramebuffer = IntArray(1)
            val scissorEnabled = GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST)
            GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, previousReadFramebuffer, 0)
            GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, previousDrawFramebuffer, 0)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, hardwareFramebuffer)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES30.glClearBufferfv(GLES30.GL_COLOR, 0, floatArrayOf(0f, 0f, 0f, 1f), 0)
            val sourceWidth = NativeCoreBridge.frameWidth().coerceIn(1, hardwareTargetWidth)
            val sourceHeight = NativeCoreBridge.frameHeight().coerceIn(1, hardwareTargetHeight)
            GLES30.glBlitFramebuffer(
                0, 0, sourceWidth, sourceHeight,
                viewport[0], viewport[1], viewport[0] + viewport[2], viewport[1] + viewport[3],
                GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR,
            )
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, previousReadFramebuffer[0])
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer[0])
            if (scissorEnabled) GLES20.glEnable(GLES20.GL_SCISSOR_TEST) else GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
        override fun onDrawFrame(gl: GL10?) {
            if (hardwareRendering) {
                renderHardwareFrame()
                return
            }
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val width = NativeCoreBridge.frameWidth(); val height = NativeCoreBridge.frameHeight()
            if (width <= 0 || height <= 0) return
            frame.position(0); if (NativeCoreBridge.copyFrame(frame) <= 0) return; frame.position(0)
            val gameAspect = width.toFloat() / height; val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
            val viewportWidth: Int; val viewportHeight: Int
            if (surfaceAspect > gameAspect) { viewportHeight = surfaceHeight; viewportWidth = (viewportHeight * gameAspect).toInt() }
            else { viewportWidth = surfaceWidth; viewportHeight = (viewportWidth / gameAspect).toInt() }
            GLES20.glViewport((surfaceWidth-viewportWidth)/2, (surfaceHeight-viewportHeight)/2, viewportWidth, viewportHeight)
            GLES20.glUseProgram(program); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            val format = if (NativeCoreBridge.pixelFormat() == 1) GLES20.GL_RGBA else GLES20.GL_RGB
            val type = if (format == GLES20.GL_RGBA) GLES20.GL_UNSIGNED_BYTE else GLES20.GL_UNSIGNED_SHORT_5_6_5
            if (width != textureWidth || height != textureHeight || format != textureFormat) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format, type, frame)
                textureWidth = width; textureHeight = height; textureFormat = format
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, frame)
            }
            vertices.position(0); GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(positionLocation)
            vertices.position(2); GLES20.glVertexAttribPointer(textureCoordinateLocation, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        fun captureHardwareThumbnail(path: String): Boolean = runCatching {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return@runCatching false
            val pixels = ByteBuffer.allocateDirect(surfaceWidth * surfaceHeight * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, surfaceWidth, surfaceHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            pixels.position(0)
            val bitmap = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(pixels)
            val flipped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, android.graphics.Matrix().apply { preScale(1f, -1f) }, true)
            FileOutputStream(File(path)).use { flipped.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle(); flipped.recycle()
            true
        }.getOrDefault(false)
        private fun shader(type: Int, code: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, code); GLES20.glCompileShader(it) }
    }

    private companion object {
        const val GL_MAJOR_VERSION = 0x821B
        const val GL_MINOR_VERSION = 0x821C
        const val ACTION_NONE = 0
        const val ACTION_RESET = 1
        const val ACTION_QUICK_SAVE = 2
        const val ACTION_QUICK_LOAD = 3
    }

    /**
     * Requests the highest GLES 3.x context the phone exposes. The stock
     * GLSurfaceView factory requests only a major version, which commonly
     * yields GLES 3.0 even on devices capable of 3.2. Play! requires 3.2 and
     * Dolphin benefits from its explicit 3.2/3.1 fallback sequence.
     */
    private class HighestGlesContextFactory : GLSurfaceView.EGLContextFactory {
        override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
            for (minor in intArrayOf(2, 1, 0)) {
                val context = egl.eglCreateContext(
                    display,
                    config,
                    EGL10.EGL_NO_CONTEXT,
                    intArrayOf(
                        EGL_CONTEXT_CLIENT_VERSION, 3,
                        EGL_CONTEXT_MINOR_VERSION_KHR, minor,
                        EGL10.EGL_NONE,
                    ),
                )
                if (context != null && context != EGL10.EGL_NO_CONTEXT) return context
                // Consume the failed probe before trying a lower version.
                egl.eglGetError()
            }
            val legacy = egl.eglCreateContext(
                display,
                config,
                EGL10.EGL_NO_CONTEXT,
                intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE),
            )
            require(legacy != null && legacy != EGL10.EGL_NO_CONTEXT) {
                "This device could not create an OpenGL ES 3 context."
            }
            return legacy
        }

        override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
            egl.eglDestroyContext(display, context)
        }

        private companion object {
            const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
            const val EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB
        }
    }
}
