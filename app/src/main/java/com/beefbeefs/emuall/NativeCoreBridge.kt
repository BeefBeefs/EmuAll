package com.beefbeefs.emuall

object NativeCoreBridge {
    init {
        System.loadLibrary("emuall_frontend")
    }

    external fun frontendVersion(): String
    external fun inspectCore(path: String): String
}
