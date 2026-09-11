package com.beefbeefs.emuall

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
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
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var recentStore: RecentGameStore
    private lateinit var recentSection: LinearLayout
    private lateinit var stateSection: LinearLayout
    private lateinit var controllerButton: Button
    private var selectedSystem = Systems.all.first()
    private val controllerMappingStore by lazy { ControllerMappingStore(this) }
    private val inputManager by lazy { getSystemService(INPUT_SERVICE) as InputManager }
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshControllerButton()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshControllerButton()
        override fun onInputDeviceChanged(deviceId: Int) = refreshControllerButton()
    }
    private val deviceGlesVersion by lazy {
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.reqGlEsVersion
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        recentStore = RecentGameStore(this)
        recentSection = findViewById(R.id.recentSection)
        stateSection = findViewById(R.id.stateSection)
        controllerButton = findViewById(R.id.controllerMappingButton)
        controllerButton.setOnClickListener { showControllerMappingDialog() }
        findViewById<TextView>(R.id.nativeStatus).text = runCatching {
            "${NativeCoreBridge.frontendVersion()} · OpenGL ES fallback ready"
        }.getOrElse { "Native frontend could not load: ${it.javaClass.simpleName}" }
        setupSystemSelector()
        inputManager.registerInputDeviceListener(inputDeviceListener, null)
    }

    override fun onResume() {
        super.onResume()
        if (::recentStore.isInitialized) renderSystemPage()
    }

    override fun onDestroy() {
        inputManager.unregisterInputDeviceListener(inputDeviceListener)
        super.onDestroy()
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
        val core = CoreRegistry.forSystem(system.id)
        val playableCore = CoreRegistry.playableForSystem(this, system.id)
        findViewById<TextView>(R.id.systemKicker).text = "${system.shortName} · ${system.integrationTier.label.uppercase()}"
        findViewById<TextView>(R.id.systemTitle).text = system.displayName
        val renderer = core?.let { GraphicsBackendSelector.describe(this, it) }
        val rendererNote = if (core != null && playableCore == null) "\nVulkan hardware-rendering support is staged but not active for this core yet." else ""
        val formats = if (system.extensions.isEmpty()) "No Android-compatible core is available yet."
        else "Core: ${core?.displayName ?: system.coreName}\nRenderer: ${renderer ?: "Not bundled"}\nRecognized: ${system.extensions.joinToString { ".$it" }}$rendererNote"
        val bios = if (system.biosRequired) "\nA legally obtained BIOS is required." else ""
        findViewById<TextView>(R.id.systemDetails).text = formats + bios
        findViewById<Button>(R.id.chooseGameButton).apply {
            isEnabled = supports(system) && playableCore != null
            text = when {
                core == null -> "Android Core Not Available"
                !supports(system) -> "GPU Requirements Not Met"
                playableCore == null -> "Vulkan Renderer Required"
                else -> "Choose ${system.shortName} Game"
            }
            setOnClickListener { openGamePicker() }
        }
        refreshControllerButton()
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
        if (CoreRegistry.playableForSystem(this, system.id) != null) launchGame(uri, name, system.id)
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
                if (CoreRegistry.playableForSystem(this, game.systemId) != null) {
                    recentStore.touch(game.uri)
                    launchGame(game.uri, game.name, game.systemId)
                }
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
        val savedCount = games.sumOf { game -> (1..3).count { stateFile(localFiles(game), it).isFile } }
        stateSection.addView(sectionHeading("SAVE STATES", "$savedCount STATES"))
        if (savedCount == 0) {
            val copy = when {
                CoreRegistry.playableForSystem(this, selectedSystem.id) != null -> "Quick Save during a game to create a screenshot-backed state here."
                CoreRegistry.forSystem(selectedSystem.id)?.requiresHardwareRendering == true -> "Save states will appear here after hardware rendering is validated for this core."
                else -> "Save states will appear here when this system's native core is added."
            }
            stateSection.addView(emptyCard(copy))
            return
        }
        games.filter { game -> (1..3).any { stateFile(localFiles(game), it).isFile } }.forEach { game ->
            val local = localFiles(game)
            stateSection.addView(label(game.name.substringBeforeLast('.'), 13f, R.color.text_primary, true).apply { setPadding(0, dp(12), 0, dp(5)) })
            val slots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            (1..3).forEach { slot ->
                val state = stateFile(local, slot)
                val slotCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    background = getDrawable(R.drawable.panel)
                }
                val thumbnail = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.BLACK)
                    contentDescription = "${game.name} Slot $slot screenshot"
                    if (thumbnailFile(state).isFile) setImageBitmap(BitmapFactory.decodeFile(thumbnailFile(state).absolutePath))
                }
                slotCard.addView(thumbnail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)))
                slotCard.addView(label(if (state.isFile) "Slot $slot" else "Slot $slot · Empty", 10f, if (state.isFile) R.color.text_primary else R.color.text_muted, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, dp(3))
                })
                if (state.isFile) {
                    slotCard.addView(actionButton("Load", true) { launchGame(game.uri, game.name, game.systemId, true, slot) })
                    slotCard.addView(actionButton("Delete", false) {
                        state.delete(); thumbnailFile(state).delete(); renderSystemPage()
                    })
                }
                slots.addView(slotCard, LinearLayout.LayoutParams(0, dp(218), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
            stateSection.addView(slots, cardParams())
        }
    }

    private fun localFiles(game: RecentGame): LocalFiles {
        val safeName = game.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val key = game.uri.toString().hashCode().toUInt().toString(16)
        val base = safeName.substringBeforeLast('.')
        val save = File(filesDir, "saves/${game.systemId}/${base}_$key.sav")
        return LocalFiles(save)
    }

    private fun stateFile(local: LocalFiles, slot: Int) = File(if (slot == 1) "${local.save.absolutePath}.quick.state" else "${local.save.absolutePath}.state.$slot")
    private fun thumbnailFile(state: File) = File("${state.absolutePath}.png")

    private fun launchGame(uri: Uri, name: String, systemId: String = selectedSystem.id, autoLoad: Boolean = false, autoLoadSlot: Int = 1) {
        val core = CoreRegistry.playableForSystem(this, systemId)
        if (core == null) {
            val registered = CoreRegistry.forSystem(systemId)
            val message = if (registered?.requiresHardwareRendering == true) {
                "${registered.displayName} needs the Vulkan hardware-rendering frontend before it can launch."
            } else {
                "${registered?.displayName ?: Systems.byId(systemId)?.coreName ?: "This core"} is not integrated yet."
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Preparing $name…", Toast.LENGTH_SHORT).show()
        Thread {
            runCatching {
                val romDirectory = File(filesDir, "roms/$systemId").apply { mkdirs() }
                val recent = recentStore.load().firstOrNull { it.uri == uri }
                val local = localFiles(RecentGame(systemId, name, uri, 0))
                local.save.parentFile?.mkdirs()
                val cached = recent?.cachedRomPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
                val rom = cached ?: prepareRom(uri, name, systemId, romDirectory)
                recentStore.touch(uri, rom.absolutePath)
                runOnUiThread {
                    startActivity(Intent(this, EmulationActivity::class.java).apply {
                        putExtra(EmulationActivity.EXTRA_ROM, rom.absolutePath)
                        putExtra(EmulationActivity.EXTRA_SAVE, local.save.absolutePath)
                        putExtra(EmulationActivity.EXTRA_CORE_LIBRARY, core.libraryName)
                        putExtra(EmulationActivity.EXTRA_CORE_NAME, core.displayName)
                        putExtra(EmulationActivity.EXTRA_SYSTEM_ID, systemId)
                        putExtra(EmulationActivity.EXTRA_AUTO_LOAD, autoLoad)
                        putExtra(EmulationActivity.EXTRA_AUTO_LOAD_SLOT, autoLoadSlot)
                    })
                }
            }.onFailure { error ->
                runOnUiThread { Toast.makeText(this, error.message ?: "Could not prepare game.", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun prepareRom(uri: Uri, name: String, systemId: String, romDirectory: File): File {
        val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val key = uri.toString().hashCode().toUInt().toString(16)
        val source = File(romDirectory, "${key}_$safeName")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Android could not open this game." }
            source.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
        }
        return when (name.substringAfterLast('.', "").lowercase()) {
            "zip" -> extractZip(source, key, systemId, romDirectory)
            "7z" -> extractSevenZip(source, key, systemId, romDirectory)
            else -> source
        }
    }

    private fun extractZip(source: File, key: String, systemId: String, outputDirectory: File): File {
        ZipInputStream(BufferedInputStream(FileInputStream(source))).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (!entry.isDirectory && isSupportedEntry(entry.name, systemId)) {
                    val output = File(outputDirectory, "${key}_${safeEntryName(entry.name)}")
                    writeLimited(archive, output)
                    return output
                }
            }
        }
        throw IllegalArgumentException("No ${Systems.byId(systemId)?.shortName ?: systemId} game was found inside the ZIP.")
    }

    private fun extractSevenZip(source: File, key: String, systemId: String, outputDirectory: File): File {
        SevenZFile(source).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (!entry.isDirectory && isSupportedEntry(entry.name, systemId)) {
                    require(entry.size <= MAX_ROM_BYTES) { "The selected ROM is too large." }
                    val output = File(outputDirectory, "${key}_${safeEntryName(entry.name)}")
                    FileOutputStream(output).use { destination ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = archive.read(buffer)
                            if (read <= 0) break
                            total += read
                            require(total <= MAX_ROM_BYTES) { "The selected ROM is too large." }
                            destination.write(buffer, 0, read)
                        }
                    }
                    return output
                }
            }
        }
        throw IllegalArgumentException("No ${Systems.byId(systemId)?.shortName ?: systemId} game was found inside the 7z archive.")
    }

    private fun writeLimited(input: java.io.InputStream, output: File) {
        FileOutputStream(output).use { destination ->
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                require(total <= MAX_ROM_BYTES) { "The selected ROM is too large." }
                destination.write(buffer, 0, read)
            }
        }
    }

    private fun isSupportedEntry(name: String, systemId: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in (Systems.byId(systemId)?.extensions ?: emptySet()) && extension !in setOf("zip", "7z")
    }

    private fun safeEntryName(name: String) = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_")

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

    private fun hasController(): Boolean = InputDevice.getDeviceIds().any { deviceId ->
        val device = InputDevice.getDevice(deviceId) ?: return@any false
        (device.sources and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)) != 0
    }

    private fun refreshControllerButton() {
        if (!::controllerButton.isInitialized) return
        val available = hasController()
        controllerButton.isEnabled = available
        controllerButton.alpha = if (available) 1f else .45f
        controllerButton.text = if (available) "Controller Mapping" else "Controller Mapping · No Controller"
    }

    private fun showControllerMappingDialog() {
        if (!hasController()) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        val rows = mutableMapOf<Int, Button>()
        fun refreshRows() {
            val mapping = controllerMappingStore.mappingFor(selectedSystem.id)
            ControllerMappingStore.logicalButtons.forEach { (buttonId, _) ->
                val button = rows[buttonId] ?: return@forEach
                val keyCode = mapping.entries.firstOrNull { it.value == buttonId }?.key
                button.text = keyCode?.let { ControllerMappingStore.keyLabel(it) } ?: "Unassigned"
            }
        }
        var awaitingButton: Int? = null
        ControllerMappingStore.logicalButtons.forEach { (buttonId, labelText) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            row.addView(label(labelText, 14f, R.color.text_primary, false), LinearLayout.LayoutParams(0, dp(46), 1f))
            val binding = Button(this).apply {
                isAllCaps = false
                textSize = 11f
                setTextColor(getColor(R.color.text_primary))
                background = getDrawable(R.drawable.button_secondary)
                setOnClickListener {
                    awaitingButton = buttonId
                    text = "Press a button…"
                }
            }
            rows[buttonId] = binding
            row.addView(binding, LinearLayout.LayoutParams(dp(138), dp(42)))
            content.addView(row)
        }
        refreshRows()
        val scroll = android.widget.ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${selectedSystem.shortName} Controller Mapping")
            .setMessage("Tap a row, then press the physical controller button to bind it.")
            .setView(scroll)
            .setNegativeButton("Reset") { _, _ -> controllerMappingStore.reset(selectedSystem.id) }
            .setPositiveButton("Done", null)
            .create()
        dialog.setOnKeyListener { _, keyCode, event ->
            val buttonId = awaitingButton
            if (buttonId != null && event.action == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_BACK) {
                controllerMappingStore.set(selectedSystem.id, keyCode, buttonId)
                awaitingButton = null
                refreshRows()
                true
            } else false
        }
        dialog.setOnShowListener {
            dialog.window?.decorView?.isFocusableInTouchMode = true
            dialog.window?.decorView?.requestFocus()
        }
        dialog.show()
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

    private data class LocalFiles(val save: File)

    companion object {
        private const val PICK_GAME = 1001
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_ROM_BYTES = 128L * 1024L * 1024L
    }
}
