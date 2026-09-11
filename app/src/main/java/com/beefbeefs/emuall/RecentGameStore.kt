package com.beefbeefs.emuall

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentGame(
    val systemId: String,
    val name: String,
    val uri: Uri,
    val playedAt: Long,
    val cachedRomPath: String? = null,
)

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
                        item.optString("cachedRomPath").takeIf { it.isNotBlank() },
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun remove(uri: Uri) = save(load().filterNot { it.uri == uri })

    /** Refreshes recency and remembers the internal ROM copy used for future launches. */
    fun touch(uri: Uri, cachedRomPath: String? = null) {
        val existing = load().firstOrNull { it.uri == uri } ?: return
        val updated = existing.copy(
            playedAt = System.currentTimeMillis(),
            cachedRomPath = cachedRomPath ?: existing.cachedRomPath,
        )
        save(listOf(updated) + load().filterNot { it.uri == uri })
    }

    private fun save(games: List<RecentGame>) {
        val data = JSONArray()
        games.forEach { game ->
            data.put(JSONObject().apply {
                put("systemId", game.systemId)
                put("name", game.name)
                put("uri", game.uri.toString())
                put("playedAt", game.playedAt)
                game.cachedRomPath?.let { put("cachedRomPath", it) }
            })
        }
        // Commit before launching the emulator so the recents list survives a
        // process death immediately after a game is selected.
        prefs.edit().putString(KEY, data.toString()).commit()
    }

    companion object {
        private const val KEY = "items"
        private const val MAX_RECENTS_PER_SYSTEM = 6
    }
}
