package com.beefbeefs.emuall

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Rendering paths supported by the native frontend. */
enum class VideoBackend(val label: String) {
    VULKAN("Vulkan"),
    OPENGL_ES("OpenGL ES"),
}

/**
 * Centralizes the Vulkan-first decision without making software-video cores
 * pretend they have a hardware renderer. Hardware cores can opt into Vulkan
 * once their native video callbacks are implemented; every other core uses the
 * proven OpenGL ES texture fallback.
 */
object GraphicsBackendSelector {
    fun vulkanAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val packageManager = context.packageManager
        return packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    }

    fun select(context: Context, core: CoreDefinition): VideoBackend {
        return if (core.preferredVideoBackend == VideoBackend.VULKAN &&
            core.supportsVulkanRendering && vulkanAvailable(context)
        ) VideoBackend.VULKAN else VideoBackend.OPENGL_ES
    }

    fun describe(context: Context, core: CoreDefinition): String {
        val selected = select(context, core)
        return when {
            selected == VideoBackend.VULKAN -> "Vulkan"
            core.preferredVideoBackend == VideoBackend.VULKAN -> "OpenGL ES fallback"
            else -> "OpenGL ES"
        }
    }
}
