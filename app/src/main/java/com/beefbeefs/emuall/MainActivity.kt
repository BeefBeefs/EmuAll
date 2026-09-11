package com.beefbeefs.emuall

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var recentStore: RecentGameStore
    private lateinit var recentSection: LinearLayout
    private lateinit var stateSection: LinearLayout
    private var selectedSystem = Systems.all.first()
    private val deviceGlesVersion by lazy {
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.reqGlEsVersion
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        recentStore = RecentGameStore(this)
        recentSection = findViewById(R.id.recentSection)
        stateSection = findViewById(R.id.stateSection)
        findViewById<TextView>(R.id.nativeStatus).text = runCatching {
            "${NativeCoreBridge.frontendVersion()} · OpenGL ES fallback ready"
        }.getOrElse { "Native frontend could not load: ${it.javaClass.simpleName}" }
        setupSystemSelector()
    }

    override fun onResume() {
        super.onResume()
        if (::recentStore.isInitialized) renderSystemPage()
    }

    private fun setupSystemSelector() {
        findViewById<Spinner>(R.id.systemSelector).apply {
            adapter = SystemAdapter()
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedSystem = Systems.all[position]
                    renderSystemPage()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    private fun renderSystemPage() {
        val system = selectedSystem
        findViewById<TextView>(R.id.systemKicker).text = "${system.shortName} · ${system.integrationTier.label.uppercase()}"
        findViewById<TextView>(R.id.systemTitle).text = system.displayName
        val formats = if (system.extensions.isEmpty()) "No Android-compatible core is available yet."
        else "Core: ${system.coreName}\nRecognized: ${system.extensions.joinToString { ".$it" }}"
        val bios = if (system.biosRequired) "\nA legally obtained BIOS is required." else ""
        findViewById<TextView>(R.id.systemDetails).text = formats + bios
        findViewById<Button>(R.id.chooseGameButton).apply {
            isEnabled = supports(system) && system.integrationTier != IntegrationTier.FUTURE
            text = when {
                system.integrationTier == IntegrationTier.FUTURE -> "Android Core Not Available"
                !supports(system) -> "GPU Requirements Not Met"
                else -> "Choose ${system.shortName} Game"
            }
            setOnClickListener { openGamePicker() }
        }
        renderRecents()
        renderStates()
    }

    private fun openGamePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/zip", "application/x-7z-compressed", "*/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_GAME)
    }

    @Deprecated("Kept for Android 8 compatibility; migrate to Activity Result API with the session screen.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_GAME || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val system = selectedSystem
        val name = displayName(uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in system.extensions) {
            Toast.makeText(this, "$name is not recognized as a ${system.shortName} game.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        recentStore.add(RecentGame(system.id, name, uri, System.currentTimeMillis()))
        renderSystemPage()
        if (system.id == "gba" && extension == "gba") launchGame(uri, name)
        else Toast.makeText(this, "$name added. ${system.coreName} integration is not playable yet.", Toast.LENGTH_LONG).show()
    }

    private fun renderRecents() {
        recentSection.removeAllViews()
        val games = recentStore.load().filter { it.systemId == selectedSystem.id }
        recentSection.addView(sectionHeading("RECENTLY PLAYED", "${games.size} GAMES"))
        if (games.isEmpty()) {
            recentSection.addView(emptyCard("No recent ${selectedSystem.shortName} games yet."))
            return
        }
        games.forEach { game ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(12), dp(12))
                background = getDrawable(R.drawable.panel)
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(game.name.substringBeforeLast('.'), 14f, R.color.text_primary, true))
                addView(label(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(game.playedAt)), 11f, R.color.text_muted, false))
            }
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(actionButton("Play", true) {
                if (game.systemId == "gba" && game.name.substringAfterLast('.', "").lowercase() == "gba") launchGame(game.uri, game.name)
                else Toast.makeText(this, "${selectedSystem.coreName} is not integrated yet.", Toast.LENGTH_SHORT).show()
            })
            row.addView(actionButton("Remove", false) {
                recentStore.remove(game.uri)
                renderSystemPage()
            })
            card.addView(row)
            recentSection.addView(card, cardParams())
        }
    }

    private fun renderStates() {
        stateSection.removeAllViews()
        val games = recentStore.load().filter { it.systemId == selectedSystem.id }
        val savedGames = games.map { it to localFiles(it).state }.filter { it.second.isFile }
        stateSection.addView(sectionHeading("SAVE STATES", "${savedGames.size} QUICK SAVES"))
        if (savedGames.isEmpty()) {
            val copy = if (selectedSystem.id == "gba") "Quick Save during a game to create a screenshot-backed state here."
            else "Save states will appear here when this system's native core is added."
            stateSection.addView(emptyCard(copy))
            return
        }
        savedGames.forEach { (game, state) ->
            val local = localFiles(game)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = getDrawable(R.drawable.panel)
            }
            val thumbnail = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.BLACK)
                contentDescription = "${game.name} quick-save screenshot"
                if (local.thumbnail.isFile) setImageBitmap(BitmapFactory.decodeFile(local.thumbnail.absolutePath))
            }
            card.addView(thumbnail, LinearLayout.LayoutParams(dp(112), dp(75)))
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(label(game.name.substringBeforeLast('.'), 13f, R.color.text_primary, true))
                addView(label("Quick Save · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastModified()))}", 10f, R.color.text_muted, false))
            }
            card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(actionButton("Load", true) { launchGame(game.uri, game.name, autoLoad = true) })
            stateSection.addView(card, cardParams())
        }
    }

    private fun localFiles(game: RecentGame): LocalFiles {
        val safeName = game.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val key = game.uri.toString().hashCode().toUInt().toString(16)
        val base = safeName.substringBeforeLast('.')
        val save = File(filesDir, "saves/gba/${base}_$key.sav")
        val state = File("${save.absolutePath}.quick.state")
        return LocalFiles(save, state, File("${state.absolutePath}.png"))
    }

    private fun launchGame(uri: Uri, name: String, autoLoad: Boolean = false) {
        Toast.makeText(this, "Preparing $name…", Toast.LENGTH_SHORT).show()
        Thread {
            runCatching {
                val romDirectory = File(filesDir, "roms/gba").apply { mkdirs() }
                val local = localFiles(RecentGame("gba", name, uri, 0))
                local.save.parentFile?.mkdirs()
                val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                val key = uri.toString().hashCode().toUInt().toString(16)
                val rom = File(romDirectory, "${key}_$safeName")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Android could not open this game." }
                    rom.outputStream().use { output -> input.copyTo(output) }
                }
                runOnUiThread {
                    startActivity(Intent(this, EmulationActivity::class.java).apply {
                        putExtra(EmulationActivity.EXTRA_ROM, rom.absolutePath)
                        putExtra(EmulationActivity.EXTRA_SAVE, local.save.absolutePath)
                        putExtra(EmulationActivity.EXTRA_AUTO_LOAD, autoLoad)
                    })
                }
            }.onFailure { error ->
                runOnUiThread { Toast.makeText(this, error.message ?: "Could not prepare game.", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "game"
    }

    private fun sectionHeading(title: String, meta: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, 17f, R.color.text_primary, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label(meta, 10f, R.color.text_muted, true))
    }

    private fun emptyCard(text: String) = label(text, 12f, R.color.text_muted, false).apply {
        setPadding(dp(14), dp(18), dp(14), dp(18))
        background = getDrawable(R.drawable.panel)
    }

    private fun actionButton(text: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 10f
        isAllCaps = false
        setTextColor(getColor(R.color.text_primary))
        background = getDrawable(if (primary) R.drawable.button_primary else R.drawable.button_secondary)
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginStart = dp(6) }
    }

    private fun cardParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(9)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(getColor(color))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 2
    }

    private inner class SystemAdapter : BaseAdapter() {
        override fun getCount() = Systems.all.size
        override fun getItem(position: Int) = Systems.all[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?) =
            systemLabel(convertView, Systems.all[position], false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?) =
            systemLabel(convertView, Systems.all[position], true)
        private fun systemLabel(convertView: View?, system: SystemDefinition, dropdown: Boolean) =
            (convertView as? TextView ?: TextView(this@MainActivity)).apply {
                text = "${system.shortName}  ·  ${system.displayName}"
                textSize = if (dropdown) 14f else 15f
                setTextColor(getColor(if (system.id == selectedSystem.id) R.color.accent else R.color.text_primary))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(if (dropdown) 12 else 8), dp(14), dp(if (dropdown) 12 else 8))
                setBackgroundColor(getColor(if (dropdown) R.color.panel_raised else android.R.color.transparent))
            }
    }

    private fun supports(system: SystemDefinition) = deviceGlesVersion >= system.minimumGles
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class LocalFiles(val save: File, val state: File, val thumbnail: File)

    companion object {
        private const val PICK_GAME = 1001
    }
}
