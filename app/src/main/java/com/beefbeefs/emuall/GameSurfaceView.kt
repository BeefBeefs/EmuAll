package com.beefbeefs.emuall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

class GameSurfaceView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {
    private val gameRenderer = GameRenderer()
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val speed = AtomicInteger(1)
    private var emulationThread: Thread? = null
    private var inputMask = 0

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(gameRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun start(corePath: String, romPath: String, savePath: String, systemDirectory: String, onResult: (String?) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        emulationThread = Thread({
            if (!NativeCoreBridge.start(corePath, romPath, savePath, systemDirectory)) {
                running.set(false)
                post { onResult(NativeCoreBridge.lastError()) }
                return@Thread
            }
            val sampleRate = NativeCoreBridge.sampleRate().coerceAtLeast(8000)
            val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val audio = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(max(minimum, sampleRate))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            val samples = ShortArray(4096)
            audio.play()
            post { onResult(null) }
            var lastSave = System.nanoTime()
            while (running.get()) {
                if (paused.get()) { Thread.sleep(12); continue }
                val started = System.nanoTime()
                NativeCoreBridge.runFrame()
                requestRender()
                val count = NativeCoreBridge.drainAudio(samples)
                if (speed.get() == 1 && count > 0) audio.write(samples, 0, count, AudioTrack.WRITE_BLOCKING)
                if (speed.get() > 1) {
                    val target = (1_000_000_000.0 / NativeCoreBridge.framesPerSecond() / speed.get()).toLong()
                    val remaining = target - (System.nanoTime() - started)
                    if (remaining > 0) Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                }
                if (System.nanoTime() - lastSave > 10_000_000_000L) { NativeCoreBridge.saveBattery(); lastSave = System.nanoTime() }
            }
            audio.pause(); audio.flush(); audio.release(); NativeCoreBridge.stop()
        }, "EmuAll-mGBA").also { it.start() }
    }

    fun setPaused(value: Boolean) = paused.set(value)
    fun isPaused() = paused.get()
    fun toggleFastForward(): Boolean { val enabled = speed.get() == 1; speed.set(if (enabled) 3 else 1); return enabled }
    fun resetGame() = NativeCoreBridge.reset()
    fun setButton(id: Int, down: Boolean) = synchronized(this) {
        inputMask = if (down) inputMask or (1 shl id) else inputMask and (1 shl id).inv()
        NativeCoreBridge.setInputMask(inputMask)
    }
    fun stop() { running.set(false); emulationThread?.join(750); emulationThread = null }

    private class GameRenderer : Renderer {
        private val frame = ByteBuffer.allocateDirect(1024 * 1024 * 4).order(ByteOrder.nativeOrder())
        private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)); position(0)
        }
        private var program = 0
        private var texture = 0
        private var surfaceWidth = 1
        private var surfaceHeight = 1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 p;attribute vec2 t;varying vec2 uv;void main(){gl_Position=vec4(p,0.,1.);uv=t;}")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float;uniform sampler2D s;varying vec2 uv;void main(){gl_FragColor=texture2D(s,uv);}")
            program = GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vertex); GLES20.glAttachShader(it, fragment); GLES20.glLinkProgram(it) }
            val textures = IntArray(1); GLES20.glGenTextures(1, textures, 0); texture = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { surfaceWidth = width; surfaceHeight = height }
        override fun onDrawFrame(gl: GL10?) {
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
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format, type, frame)
            val position = GLES20.glGetAttribLocation(program, "p"); val textureCoordinate = GLES20.glGetAttribLocation(program, "t")
            vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(position)
            vertices.position(2); GLES20.glVertexAttribPointer(textureCoordinate, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(textureCoordinate)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        private fun shader(type: Int, code: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, code); GLES20.glCompileShader(it) }
    }
}
