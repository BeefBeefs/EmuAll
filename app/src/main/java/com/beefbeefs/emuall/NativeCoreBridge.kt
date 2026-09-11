package com.beefbeefs.emuall

object NativeCoreBridge {
    private val loadedCoreLibraries = mutableSetOf<String>()

    init {
        loadCoreLibrary(CoreRegistry.forSystem("gba")?.libraryName ?: "libmgba_libretro.so")
        System.loadLibrary("emuall_frontend")
    }

    /** Loads an additional registry-selected core into Android's native namespace. */
    @Synchronized
    fun ensureCoreLoaded(libraryName: String) = loadCoreLibrary(libraryName)

    private fun loadCoreLibrary(libraryName: String) {
        val soname = libraryName.removePrefix("lib").removeSuffix(".so")
        if (loadedCoreLibraries.add(soname)) System.loadLibrary(soname)
    }

    external fun frontendVersion(): String
    external fun start(corePath: String, romPath: String, savePath: String, systemDirectory: String, hardwareRendering: Boolean): Boolean
    external fun setHardwareFramebuffer(framebuffer: Int)
    external fun hardwareContextReset()
    external fun runFrame()
    external fun copyFrame(destination: java.nio.ByteBuffer): Int
    external fun frameWidth(): Int
    external fun frameHeight(): Int
    external fun videoAspectRatio(): Float
    external fun pixelFormat(): Int
    external fun drainAudio(destination: ShortArray): Int
    external fun sampleRate(): Int
    external fun framesPerSecond(): Double
    external fun setInputMask(mask: Int)
    external fun reset()
    external fun quickSave(path: String): Boolean
    external fun quickLoad(path: String): Boolean
    external fun saveBattery()
    external fun stop()
    external fun lastError(): String
}
