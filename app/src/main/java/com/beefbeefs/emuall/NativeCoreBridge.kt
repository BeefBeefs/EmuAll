package com.beefbeefs.emuall

object NativeCoreBridge {
    init {
        System.loadLibrary("mgba_libretro")
        System.loadLibrary("emuall_frontend")
    }

    external fun frontendVersion(): String
    external fun start(corePath: String, romPath: String, savePath: String, systemDirectory: String): Boolean
    external fun runFrame()
    external fun copyFrame(destination: java.nio.ByteBuffer): Int
    external fun frameWidth(): Int
    external fun frameHeight(): Int
    external fun pixelFormat(): Int
    external fun drainAudio(destination: ShortArray): Int
    external fun sampleRate(): Int
    external fun framesPerSecond(): Double
    external fun setInputMask(mask: Int)
    external fun reset()
    external fun saveBattery()
    external fun stop()
    external fun lastError(): String
}
