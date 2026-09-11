package com.beefbeefs.emuall

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentGame(val systemId: String, val name: String, val uri: Uri, val playedAt: Long)

class RecentGameStore(context: Context) {
    private val prefs = context.getSharedPreferences("recent_games", Context.MODE_PRIVATE)

    fun add(game: RecentGame) {
        val games = load().filterNot { it.uri == game.uri }.toMutableList()
        games.add(0, game)
        val retained = games.groupBy { it.systemId }
            .values
            .flatMap { perSystem -> perSystem.sortedByDescending { it.playedAt }.take(MAX_RECENTS_PER_SYSTEM) }
            .sortedByDescending { it.playedAt }
        save(retained)
    }

    fun load(): List<RecentGame> = runCatching {
        val data = JSONArray(prefs.getString(KEY, "[]"))
        buildList {
            for (index in 0 until data.length()) {
                val item = data.getJSONObject(index)
                add(
                    RecentGame(
                        item.getString("systemId"),
                        item.getString("name"),
                        Uri.parse(item.getString("uri")),
                        item.getLong("playedAt"),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun save(games: List<RecentGame>) {
        val data = JSONArray()
        games.forEach { game ->
            data.put(JSONObject().apply {
                put("systemId", game.systemId)
                put("name", game.name)
                put("uri", game.uri.toString())
                put("playedAt", game.playedAt)
            })
        }
        prefs.edit().putString(KEY, data.toString()).apply()
    }

    companion object {
        private const val KEY = "items"
        private const val MAX_RECENTS_PER_SYSTEM = 6
    }
}
