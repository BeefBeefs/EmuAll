package com.beefbeefs.emuall

/** A core package that can be selected by the native frontend. */
data class CoreDefinition(
    val id: String,
    val displayName: String,
    val libraryName: String,
    val supportedSystems: Set<String>,
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
    )

    fun byId(id: String?): CoreDefinition? = bundled.firstOrNull { it.id == id }

    fun forSystem(systemId: String): CoreDefinition? {
        val system = Systems.byId(systemId) ?: return null
        val core = byId(system.coreId) ?: return null
        return core.takeIf { systemId in it.supportedSystems }
    }
}
