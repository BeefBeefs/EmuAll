package com.beefbeefs.emuall

import android.content.Context

/** Persists normalized virtual-control group positions per system and orientation. */
class VirtualControlLayoutStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    data class Position(val x: Float, val y: Float)

    fun position(systemId: String, orientation: Int, group: String): Position? {
        val xKey = key(systemId, orientation, group, "x")
        val yKey = key(systemId, orientation, group, "y")
        if (!preferences.contains(xKey) || !preferences.contains(yKey)) return null
        return Position(
            preferences.getFloat(xKey, 0f).coerceIn(0f, 1f),
            preferences.getFloat(yKey, 0f).coerceIn(0f, 1f),
        )
    }

    fun save(systemId: String, orientation: Int, positions: Map<String, Position>) {
        preferences.edit().apply {
            positions.forEach { (group, position) ->
                putFloat(key(systemId, orientation, group, "x"), position.x.coerceIn(0f, 1f))
                putFloat(key(systemId, orientation, group, "y"), position.y.coerceIn(0f, 1f))
            }
        }.apply()
    }

    private fun key(systemId: String, orientation: Int, group: String, axis: String) =
        "$systemId:$orientation:$group:$axis"

    private companion object {
        const val PREFERENCES = "virtual_control_layouts"
    }
}
