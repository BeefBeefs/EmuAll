package com.beefbeefs.emuall

data class SystemDefinition(
    val id: String,
    val shortName: String,
    val displayName: String,
    val coreName: String,
    val extensions: Set<String>,
    val biosRequired: Boolean = false,
    val minimumGles: Int = 0x00020000,
    val integrationTier: IntegrationTier = IntegrationTier.STANDARD,
)

enum class IntegrationTier(val label: String) {
    STANDARD("Standard core"),
    HARDWARE("Hardware-rendered core"),
    FUTURE("Awaiting a validated Android core"),
}

object Systems {
    val all = listOf(
        SystemDefinition("gba", "GBA", "Game Boy Advance", "mGBA", setOf("gba", "zip", "7z")),
        SystemDefinition("gbc", "GB / GBC", "Game Boy / Color", "Gambatte", setOf("gb", "gbc", "zip", "7z")),
        SystemDefinition("nes", "NES", "NES / Famicom", "FCEUmm", setOf("nes", "fds", "unf", "unif", "zip", "7z")),
        SystemDefinition("snes", "SNES", "Super Nintendo", "Snes9x", setOf("sfc", "smc", "fig", "gd3", "gd7", "dx2", "bsx", "swc", "zip", "7z")),
        SystemDefinition("genesis", "GENESIS", "Genesis / Mega Drive", "Genesis Plus GX", setOf("md", "gen", "bin", "smd", "zip", "7z")),
        SystemDefinition("atari2600", "ATARI", "Atari 2600", "Stella 2014", setOf("a26", "bin", "rom", "zip", "7z")),
        SystemDefinition("n64", "N64", "Nintendo 64", "Mupen64Plus-Next", setOf("z64", "n64", "v64", "zip", "7z")),
        SystemDefinition("nds", "NDS", "Nintendo DS", "DeSmuME", setOf("nds", "zip", "7z")),
        SystemDefinition("psp", "PSP", "PlayStation Portable", "PPSSPP", setOf("iso", "cso", "pbp", "zip", "7z")),
        SystemDefinition("ps1", "PS1", "PlayStation", "PCSX-ReARMed", setOf("chd", "bin", "cue", "img", "mdf", "pbp", "toc", "cbn", "m3u", "ccd", "zip", "7z"), true),
        SystemDefinition("saturn", "SATURN", "Sega Saturn", "Yabause", setOf("chd", "iso", "cue", "bin", "zip", "7z"), true),
        SystemDefinition(
            "dreamcast", "DREAMCAST", "Dreamcast / NAOMI", "Flycast",
            setOf("cdi", "gdi", "chd", "cue", "bin", "elf", "zip", "7z", "lst", "dat", "m3u"),
            minimumGles = 0x00030000,
            integrationTier = IntegrationTier.HARDWARE,
        ),
        SystemDefinition(
            "ps2", "PS2", "PlayStation 2", "Play!",
            setOf("chd", "cso", "cue", "elf", "iso", "isz"),
            minimumGles = 0x00030002,
            integrationTier = IntegrationTier.HARDWARE,
        ),
        SystemDefinition(
            "gamecube", "GC / WII", "GameCube / Wii", "Dolphin",
            setOf("elf", "iso", "gcm", "dol", "tgc", "wbfs", "ciso", "gcz", "wad", "rvz", "m3u"),
            minimumGles = 0x00030000,
            integrationTier = IntegrationTier.HARDWARE,
        ),
        SystemDefinition(
            "xbox", "XBOX", "Original Xbox", "No validated Android core yet",
            emptySet(),
            minimumGles = 0x00030000,
            integrationTier = IntegrationTier.FUTURE,
        ),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
