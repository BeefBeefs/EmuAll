package com.beefbeefs.emuall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.graphics.Bitmap
import android.opengl.GLES20
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
    private var statusCallback: ((String) -> Unit)? = null

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
            renderMode = RENDERMODE_CONTINUOUSLY
            requestRender()
            return
        }
        emulationThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            if (!NativeCoreBridge.start(corePath, romPath, savePath, systemDirectory, false)) {
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
                    NativeCoreBridge.runFrame()
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
        if (!NativeCoreBridge.start(session.corePath, session.romPath, session.savePath, session.systemDirectory, true)) {
            running.set(false)
            statusCallback?.invoke(NativeCoreBridge.lastError())
            return
        }
        hardwareStarted.set(true)
        startHardwareAudio(NativeCoreBridge.sampleRate().coerceAtLeast(8000))
        statusCallback?.invoke("${session.coreName} · ${session.videoBackend.label} hardware context · Starting")
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
        // Establish a non-zero viewport before starting a core.  Several
        // hardware renderers inspect GL_VIEWPORT while their context_reset
        // callback initializes framebuffers during the first start.
        GLES20.glViewport(0, 0, getWidth(), getHeight())
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        startHardwareSessionIfNeeded()
        if (!hardwareStarted.get()) return
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
        NativeCoreBridge.runFrame()
        saveThumbnail?.let { gameRenderer.captureHardwareThumbnail(it) }
    }

    private fun stopHardwareSession() {
        hardwareAudioRunning?.set(false)
        hardwareAudioThread?.join(1000)
        hardwareAudioThread = null
        hardwareAudioRunning = null
        if (hardwareStarted.compareAndSet(true, false)) NativeCoreBridge.stop()
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

    fun setPaused(value: Boolean) = paused.set(value)
    fun isPaused() = paused.get()
    fun toggleFastForward(): Boolean { val enabled = speed.get() == 1; speed.set(if (enabled) 3 else 1); return enabled }
    fun resetGame() = pendingAction.set(ACTION_RESET)
    fun quickSave(slot: Int = 1) { pendingSlot.set(slot.coerceIn(1, 3)); pendingAction.set(ACTION_QUICK_SAVE) }
    fun quickLoad(slot: Int = 1) { pendingSlot.set(slot.coerceIn(1, 3)); pendingAction.set(ACTION_QUICK_LOAD) }
    fun setButton(id: Int, down: Boolean) = synchronized(this) {
        inputMask = if (down) inputMask or (1 shl id) else inputMask and (1 shl id).inv()
        NativeCoreBridge.setInputMask(inputMask)
    }
    fun stop() {
        running.set(false)
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
        private var positionLocation = -1
        private var textureCoordinateLocation = -1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            textureWidth = 0
            textureHeight = 0
            textureFormat = 0
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
            if (hardwareRendering && hardwareStarted.get()) NativeCoreBridge.hardwareContextReset()
            post { requestRender() }
        }
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width; surfaceHeight = height
            requestRender()
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
        const val ACTION_NONE = 0
        const val ACTION_RESET = 1
        const val ACTION_QUICK_SAVE = 2
        const val ACTION_QUICK_LOAD = 3
    }
}
