package com.beefbeefs.emuall

object NativeCoreBridge {
    init {
        // Core DSOs are opened by the native frontend from their absolute
        // extracted APK path.  Preloading every core with System.loadLibrary
        // puts their large static symbol sets in one global namespace (Dolphin
        // and Flycast share names such as Buf_*), which can make a later core
        // resolve against the wrong implementation and crash on startup.
        System.loadLibrary("emuall_frontend")
    }

    external fun frontendVersion(): String
    external fun start(corePath: String, romPath: String, savePath: String, systemDirectory: String, hardwareRendering: Boolean, assetManager: android.content.res.AssetManager): Boolean
    external fun setHardwareFramebuffer(framebuffer: Int)
    external fun setGraphicsContextVersion(major: Int, minor: Int)
    external fun hardwareContextDestroy()
    external fun hardwareContextReset()
    external fun runFrame(): Boolean
    external fun copyFrame(destination: java.nio.ByteBuffer): Int
    external fun frameWidth(): Int
    external fun frameHeight(): Int
    external fun videoAspectRatio(): Float
    external fun pixelFormat(): Int
    external fun drainAudio(destination: ShortArray): Int
    external fun sampleRate(): Int
    external fun framesPerSecond(): Double
    external fun setInputMask(mask: Int)
    external fun setAnalog(stick: Int, x: Int, y: Int)
    external fun reset()
    external fun quickSave(path: String): Boolean
    external fun quickLoad(path: String): Boolean
    external fun saveBattery()
    external fun stop()
    external fun lastError(): String
    external fun diagnostics(): String
    external fun diagnosticMarker(message: String)
}
