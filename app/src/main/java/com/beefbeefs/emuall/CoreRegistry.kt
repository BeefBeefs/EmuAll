package com.beefbeefs.emuall

/** A core package that can be selected by the native frontend. */
data class CoreDefinition(
    val id: String,
    val displayName: String,
    val libraryName: String,
    val supportedSystems: Set<String>,
    val preferredVideoBackend: VideoBackend = VideoBackend.OPENGL_ES,
    val supportsVulkanRendering: Boolean = false,
    val supportsOpenGlHardware: Boolean = false,
    val requiresHardwareRendering: Boolean = false,
)

/**
 * The single source of truth for bundled native cores.
 *
 * Adding a core later is deliberately small: package its shared library, add one
 * definition here, and assign that core id to the systems it supports.
 */
object CoreRegistry {
    private val bundled = listOf(
        CoreDefinition(
            id = "mgba",
            displayName = "mGBA",
            libraryName = "libmgba_libretro.so",
            supportedSystems = setOf("gba", "gbc"),
        ),
        CoreDefinition("fceumm", "FCEUmm", "libfceumm_libretro_android.so", setOf("nes")),
        CoreDefinition("snes9x", "Snes9x", "libsnes9x_libretro_android.so", setOf("snes")),
        CoreDefinition("genesis_plus_gx", "Genesis Plus GX", "libgenesis_plus_gx_libretro_android.so", setOf("genesis")),
        CoreDefinition("pcsx_rearmed", "PCSX-ReARMed", "libpcsx_rearmed_libretro_android.so", setOf("ps1")),
        CoreDefinition(
            "ppsspp", "PPSSPP", "libppsspp_libretro_android.so", setOf("psp"),
            preferredVideoBackend = VideoBackend.VULKAN,
            supportsOpenGlHardware = true,
            requiresHardwareRendering = true,
        ),
        CoreDefinition(
            "mupen64plus_next_gles3", "Mupen64Plus-Next", "libmupen64plus_next_gles3_libretro_android.so", setOf("n64"),
            preferredVideoBackend = VideoBackend.VULKAN,
            supportsOpenGlHardware = true,
            requiresHardwareRendering = true,
        ),
        CoreDefinition(
            "flycast", "Flycast", "libflycast_libretro_android.so", setOf("dreamcast"),
            preferredVideoBackend = VideoBackend.VULKAN,
            supportsOpenGlHardware = true,
            requiresHardwareRendering = true,
        ),
        CoreDefinition(
            "dolphin", "Dolphin", "libdolphin_libretro_android.so", setOf("gamecube"),
            preferredVideoBackend = VideoBackend.VULKAN,
            supportsOpenGlHardware = true,
            requiresHardwareRendering = true,
        ),
        CoreDefinition(
            "play", "Play!", "libplay_libretro_android.so", setOf("ps2"),
            preferredVideoBackend = VideoBackend.OPENGL_ES,
            supportsOpenGlHardware = true,
            requiresHardwareRendering = true,
        ),
    )

    fun byId(id: String?): CoreDefinition? = bundled.firstOrNull { it.id == id }

    fun forSystem(systemId: String): CoreDefinition? {
        val system = Systems.byId(systemId) ?: return null
        val core = byId(system.coreId) ?: return null
        return core.takeIf { systemId in it.supportedSystems }
    }

    /** Returns a core only when the current frontend can render it safely. */
    fun playableForSystem(context: android.content.Context, systemId: String): CoreDefinition? {
        val system = Systems.byId(systemId) ?: return null
        val core = forSystem(systemId) ?: return null
        if (GraphicsBackendSelector.glesVersion(context) < system.minimumGles) return null
        if (!core.requiresHardwareRendering) return core
        return core.takeIf { GraphicsBackendSelector.canRender(context, core) }
    }
}
