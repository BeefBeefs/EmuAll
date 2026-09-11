package com.beefbeefs.emuall

import android.content.Context
import android.view.KeyEvent

/** Persists physical-controller bindings independently for each emulated system. */
class ControllerMappingStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun mappingFor(systemId: String): Map<Int, Int> {
        val mapping = defaultMapping().toMutableMap()
        val encoded = preferences.getString(systemId, null) ?: return mapping
        encoded.split(',').forEach { pair ->
            val parts = pair.split(':', limit = 2)
            if (parts.size == 2) {
                val keyCode = parts[0].toIntOrNull()
                val buttonId = parts[1].toIntOrNull()
                if (keyCode != null && buttonId != null && buttonId in SUPPORTED_BUTTON_IDS) {
                    mapping[keyCode] = buttonId
                }
            }
        }
        return mapping
    }

    fun set(systemId: String, keyCode: Int, buttonId: Int) {
        if (buttonId !in SUPPORTED_BUTTON_IDS) return
        val mapping = mappingFor(systemId).toMutableMap()
        mapping.entries.removeAll { it.value == buttonId }
        mapping[keyCode] = buttonId
        preferences.edit().putString(systemId, encode(mapping)).apply()
    }

    fun reset(systemId: String) = preferences.edit().remove(systemId).apply()

    private fun encode(mapping: Map<Int, Int>) = mapping.entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}:${it.value}" }

    companion object {
        private const val PREFERENCES = "controller_mappings"
        private val SUPPORTED_BUTTON_IDS = setOf(
            BUTTON_B, BUTTON_SELECT, BUTTON_START, BUTTON_UP, BUTTON_DOWN,
            BUTTON_LEFT, BUTTON_RIGHT, BUTTON_A, BUTTON_Y, BUTTON_X,
            BUTTON_L, BUTTON_R, BUTTON_L2, BUTTON_R2, BUTTON_L3, BUTTON_R3,
        )

        val logicalButtons = listOf(
            BUTTON_UP to "Up",
            BUTTON_DOWN to "Down",
            BUTTON_LEFT to "Left",
            BUTTON_RIGHT to "Right",
            BUTTON_A to "A",
            BUTTON_B to "B",
            BUTTON_X to "X",
            BUTTON_Y to "Y",
            BUTTON_L to "L",
            BUTTON_R to "R",
            BUTTON_L2 to "L2 / Z",
            BUTTON_R2 to "R2",
            BUTTON_L3 to "L3",
            BUTTON_R3 to "R3",
            BUTTON_SELECT to "Select",
            BUTTON_START to "Start",
        )

        const val BUTTON_B = 0
        const val BUTTON_SELECT = 2
        const val BUTTON_START = 3
        const val BUTTON_UP = 4
        const val BUTTON_DOWN = 5
        const val BUTTON_LEFT = 6
        const val BUTTON_RIGHT = 7
        const val BUTTON_A = 8
        const val BUTTON_X = 9
        const val BUTTON_L = 10
        const val BUTTON_R = 11
        const val BUTTON_L2 = 12
        const val BUTTON_R2 = 13
        const val BUTTON_L3 = 14
        const val BUTTON_R3 = 15
        const val BUTTON_Y = 1

        fun defaultMapping(): Map<Int, Int> = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to BUTTON_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to BUTTON_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to BUTTON_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to BUTTON_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A to BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B to BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X to BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y to BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1 to BUTTON_L,
            KeyEvent.KEYCODE_BUTTON_R1 to BUTTON_R,
            KeyEvent.KEYCODE_BUTTON_L2 to BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2 to BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL to BUTTON_L3,
            KeyEvent.KEYCODE_BUTTON_THUMBR to BUTTON_R3,
            KeyEvent.KEYCODE_BUTTON_SELECT to BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_START to BUTTON_START,
        )

        fun keyLabel(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .replace('_', ' ')
    }
}
