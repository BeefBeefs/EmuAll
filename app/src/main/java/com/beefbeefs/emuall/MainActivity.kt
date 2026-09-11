package com.beefbeefs.emuall

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
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
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
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
    private data class SelectedDocument(val uri: Uri, val name: String)
    private lateinit var recentStore: RecentGameStore
    private lateinit var homeSection: LinearLayout
    private lateinit var homeRecentSection: LinearLayout
    private lateinit var systemContent: LinearLayout
    private lateinit var recentSection: LinearLayout
    private lateinit var stateSection: LinearLayout
    private lateinit var controllerButton: Button
    private lateinit var preparationPanel: LinearLayout
    private lateinit var preparationTitle: TextView
    private lateinit var preparationStatus: TextView
    private lateinit var preparationProgress: ProgressBar
    private var preparationInProgress = false
    private var showingHome = true
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
        homeSection = findViewById(R.id.homeSection)
        homeRecentSection = findViewById(R.id.homeRecentSection)
        systemContent = findViewById(R.id.systemContent)
        recentSection = findViewById(R.id.recentSection)
        stateSection = findViewById(R.id.stateSection)
        controllerButton = findViewById(R.id.controllerMappingButton)
        preparationPanel = findViewById(R.id.preparationPanel)
        preparationTitle = findViewById(R.id.preparationTitle)
        preparationStatus = findViewById(R.id.preparationStatus)
        preparationProgress = findViewById(R.id.preparationProgress)
        controllerButton.setOnClickListener { showControllerMappingDialog() }
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        findViewById<TextView>(R.id.buildLabel).text = "NATIVE ENGINE · BUILD ${packageInfo.longVersionCode} · v${packageInfo.versionName ?: "unknown"}"
        findViewById<TextView>(R.id.nativeStatus).text = runCatching {
            "${NativeCoreBridge.frontendVersion()} · OpenGL ES fallback ready"
        }.getOrElse { "Native frontend could not load: ${it.javaClass.simpleName}" }
        findViewById<Button>(R.id.preferencesButton).setOnClickListener { showPreferencesDialog() }
        findViewById<Button>(R.id.homeReportButton).setOnClickListener { showLastEmulationReport() }
        findViewById<Button>(R.id.systemReportButton).setOnClickListener { showLastEmulationReport() }
        setupSystemSelector()
        renderHome()
        inputManager.registerInputDeviceListener(inputDeviceListener, null)
    }

    override fun onResume() {
        super.onResume()
        if (::recentStore.isInitialized) {
            if (showingHome) renderHome() else renderSystemPage()
        }
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
                    if (position == 0) {
                        showingHome = true
                        renderHome()
                    } else {
                        selectedSystem = Systems.all[position - 1]
                        showingHome = false
                        renderSystemPage()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    private fun renderSystemPage() {
        showingHome = false
        homeSection.visibility = View.GONE
        systemContent.visibility = View.VISIBLE
        val system = selectedSystem
        val core = CoreRegistry.forSystem(system.id)
        val playableCore = CoreRegistry.playableForSystem(this, system.id)
        findViewById<TextView>(R.id.systemKicker).text = "${system.shortName} · ${system.integrationTier.label.uppercase()}"
        findViewById<TextView>(R.id.systemTitle).text = system.displayName
        val renderer = core?.let { GraphicsBackendSelector.describe(this, it) }
        val rendererNote = if (core != null && playableCore == null) "\nThis core needs a compatible hardware-rendering device." else ""
        val looseDiscNote = if (system.id == "dreamcast") {
            "\nLoose CUE/GDI: long-press and select the descriptor plus every track together."
        } else ""
        val formats = if (system.extensions.isEmpty()) "No Android-compatible core is available yet."
        else "Core: ${core?.displayName ?: system.coreName}\nRenderer: ${renderer ?: "Not bundled"}\nRecognized: ${system.extensions.joinToString { ".$it" }}$rendererNote$looseDiscNote"
        findViewById<TextView>(R.id.systemDetails).text = formats
        findViewById<Button>(R.id.chooseGameButton).apply {
            isEnabled = supports(system) && playableCore != null
            text = when {
                core == null -> "Android Core Not Available"
                !supports(system) -> "GPU Requirements Not Met"
                playableCore == null -> "Hardware Renderer Unavailable"
                else -> "Choose ${system.shortName} Game"
            }
            setOnClickListener { openGamePicker() }
        }
        refreshControllerButton()
        refreshDiagnosticActions()
        renderRecents()
        renderStates()
    }

    private fun renderHome() {
        showingHome = true
        homeSection.visibility = View.VISIBLE
        systemContent.visibility = View.GONE
        findViewById<TextView>(R.id.graphicsPreferenceValue).text = if (AppPreferences.preferVulkan(this)) {
            "Vulkan first when supported · OpenGL ES fallback"
        } else {
            "OpenGL ES selected"
        }
        findViewById<TextView>(R.id.controllerPreferenceValue).text = if (hasController()) {
            "Controller detected · mappings are per system"
        } else {
            "No controller detected · virtual controls available"
        }
        renderHomeRecents()
        refreshDiagnosticActions()
    }

    private fun latestDiagnosticFile(): File? {
        val root = File(filesDir, "saves")
        return root.listFiles().orEmpty()
            .map { File(it, "last_core_diagnostics.txt") }
            .filter { it.isFile && it.length() > 0L }
            .maxByOrNull { it.lastModified() }
    }

    private fun refreshDiagnosticActions() {
        val report = latestDiagnosticFile()
        findViewById<View>(R.id.homeReportCard).visibility = if (report == null) View.GONE else View.VISIBLE
        findViewById<View>(R.id.systemReportButton).visibility = if (report == null) View.GONE else View.VISIBLE
        if (report != null) {
            val reportSystem = Systems.byId(report.parentFile?.name.orEmpty())?.displayName
                ?: report.parentFile?.name.orEmpty().uppercase()
            findViewById<TextView>(R.id.homeReportSummary).text =
                "$reportSystem · saved even if the emulator screen or app closed"
        }
    }

    private fun showLastEmulationReport() {
        val file = latestDiagnosticFile()
        if (file == null) {
            Toast.makeText(this, "No emulation report is available yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val nativeReport = runCatching { file.readText() }.getOrElse { "Could not read report: ${it.message}" }
        val exitReport = recentCrashSummary(file.lastModified())
        val report = if (exitReport == null) nativeReport else "$nativeReport\n\n$exitReport"
        val text = TextView(this).apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            this.text = report
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle("Last emulation report")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("EmuAll report", report))
                Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun recentCrashSummary(reportTime: Long): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val exits = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
            .getHistoricalProcessExitReasons(packageName, 0, 5)
        val exit = exits.firstOrNull {
            it.reason in setOf(
                android.app.ApplicationExitInfo.REASON_CRASH,
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                android.app.ApplicationExitInfo.REASON_ANR,
            ) && kotlin.math.abs(it.timestamp - reportTime) < 120_000L
        } ?: return null
        val reason = when (exit.reason) {
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            android.app.ApplicationExitInfo.REASON_ANR -> "App not responding"
            else -> "Java crash"
        }
        return buildString {
            append("Android process exit: $reason (status ${exit.status})")
            exit.description?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
        }
    }

    private fun renderHomeRecents() {
        homeRecentSection.removeAllViews()
        val games = recentStore.load().sortedByDescending { it.playedAt }.take(HOME_RECENT_LIMIT)
        homeRecentSection.addView(sectionHeading("RECENTLY PLAYED", "${games.size} GAMES"))
        if (games.isEmpty()) {
            homeRecentSection.addView(emptyCard("Games you launch will appear here across every system."))
            return
        }
        games.forEach { game ->
            val system = Systems.byId(game.systemId)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(12), dp(12))
                background = getDrawable(R.drawable.panel)
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(game.name.substringBeforeLast('.'), 14f, R.color.text_primary, true))
                addView(label(
                    "${system?.shortName ?: game.systemId.uppercase()} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(game.playedAt))}",
                    11f,
                    R.color.text_muted,
                    false,
                ))
            }
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(actionButton("Play", true) {
                if (CoreRegistry.playableForSystem(this, game.systemId) != null) {
                    recentStore.touch(game.uri)
                    launchGame(game.uri, game.name, game.systemId)
                } else {
                    Toast.makeText(this, "${system?.coreName ?: "This system"} is not playable on this device.", Toast.LENGTH_SHORT).show()
                }
            })
            row.addView(actionButton("Remove", false) {
                recentStore.remove(game.uri)
                renderHome()
            })
            card.addView(row)
            homeRecentSection.addView(card, cardParams())
        }
    }

    private fun showPreferencesDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        val vulkan = Switch(this).apply {
            text = "Prefer Vulkan when available"
            setTextColor(getColor(R.color.text_primary))
            isChecked = AppPreferences.preferVulkan(this@MainActivity)
        }
        val keepScreenOn = Switch(this).apply {
            text = "Keep screen on while emulating"
            setTextColor(getColor(R.color.text_primary))
            isChecked = AppPreferences.keepScreenOn(this@MainActivity)
        }
        content.addView(vulkan)
        content.addView(keepScreenOn)
        AlertDialog.Builder(this)
            .setTitle("Preferences")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                AppPreferences.setPreferVulkan(this, vulkan.isChecked)
                AppPreferences.setKeepScreenOn(this, keepScreenOn.isChecked)
                renderHome()
            }
            .show()
    }

    private fun openGamePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/zip", "application/x-7z-compressed", "*/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, selectedSystem.id in setOf("dreamcast", "ps1", "ps2"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_GAME)
    }

    @Deprecated("Kept for Android 8 compatibility; migrate to Activity Result API with the session screen.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_GAME || resultCode != Activity.RESULT_OK) return
        val system = selectedSystem
        val uris = buildList {
            data?.clipData?.let { clips ->
                for (index in 0 until clips.itemCount) add(clips.getItemAt(index).uri)
            }
            data?.data?.let(::add)
        }.distinct()
        val documents = uris.map { SelectedDocument(it, displayName(it)) }
        val primary = documents
            .filter { it.name.substringAfterLast('.', "").lowercase() in system.extensions }
            .minByOrNull { archiveEntryPriority(it.name, system.id) }
        if (primary == null) {
            Toast.makeText(this, "The selection does not contain a recognized ${system.shortName} game.", Toast.LENGTH_LONG).show()
            return
        }
        documents.forEach { document ->
            runCatching { contentResolver.takePersistableUriPermission(document.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val companions = documents.filterNot { it.uri == primary.uri }
        recentStore.add(RecentGame(system.id, primary.name, primary.uri, System.currentTimeMillis()))
        renderSystemPage()
        if (CoreRegistry.playableForSystem(this, system.id) != null) {
            launchGame(primary.uri, primary.name, system.id, companionDocuments = companions)
        } else {
            Toast.makeText(this, "${primary.name} added. ${system.coreName} integration is not playable yet.", Toast.LENGTH_LONG).show()
        }
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

    private fun launchGame(
        uri: Uri,
        name: String,
        systemId: String = selectedSystem.id,
        autoLoad: Boolean = false,
        autoLoadSlot: Int = 1,
        companionDocuments: List<SelectedDocument> = emptyList(),
    ) {
        val core = CoreRegistry.playableForSystem(this, systemId)
        if (core == null) {
            val registered = CoreRegistry.forSystem(systemId)
            val message = if (registered?.requiresHardwareRendering == true) {
                "${registered.displayName} needs a compatible hardware-rendering device before it can launch."
            } else {
                "${registered?.displayName ?: Systems.byId(systemId)?.coreName ?: "This core"} is not integrated yet."
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        if (preparationInProgress) {
            Toast.makeText(this, "A game is already being prepared.", Toast.LENGTH_SHORT).show()
            return
        }
        preparationInProgress = true
        showPreparation(name)
        val report = createPreparationReporter()
        Thread {
            try {
                val romDirectory = File(filesDir, "roms/$systemId").apply { mkdirs() }
                val recent = recentStore.load().firstOrNull { it.uri == uri }
                val local = localFiles(RecentGame(systemId, name, uri, 0))
                local.save.parentFile?.mkdirs()
                report("Checking file and storage", 0L, -1L)
                val cached = recent?.cachedRomPath?.let(::File)?.takeIf {
                    companionDocuments.isEmpty() && it.isFile && it.length() > 0L &&
                        isReusablePreparedFile(it, name, systemId)
                }
                val rom = if (cached != null) {
                    report("Using prepared game copy", 1L, 1L)
                    cached
                } else {
                    prepareRom(uri, name, systemId, romDirectory, report, companionDocuments)
                }
                postPreparation("Validating ${core.displayName}", -1L, -1L)
                validatePreparedRom(rom, systemId)
                recentStore.touch(uri, rom.absolutePath)
                postPreparation("Launching ${core.displayName}", -1L, -1L)
                val systemDirectory = systemDirectoryFor(systemId, report)
                val intent = Intent(this, EmulationActivity::class.java).apply {
                    putExtra(EmulationActivity.EXTRA_ROM, rom.absolutePath)
                    putExtra(EmulationActivity.EXTRA_SAVE, local.save.absolutePath)
                    putExtra(EmulationActivity.EXTRA_CORE_LIBRARY, core.libraryName)
                    putExtra(EmulationActivity.EXTRA_CORE_NAME, core.displayName)
                    putExtra(EmulationActivity.EXTRA_SYSTEM_ID, systemId)
                    putExtra(EmulationActivity.EXTRA_SYSTEM_DIRECTORY, systemDirectory)
                    putExtra(EmulationActivity.EXTRA_AUTO_LOAD, autoLoad)
                    putExtra(EmulationActivity.EXTRA_AUTO_LOAD_SLOT, autoLoadSlot)
                }
                runOnUiThread {
                    try {
                        preparationStatus.text = "Opening ${core.displayName}…"
                        startActivity(intent)
                        preparationInProgress = false
                        preparationPanel.visibility = View.GONE
                    } catch (error: Throwable) {
                        preparationInProgress = false
                        val message = preparationErrorMessage(error)
                        persistFrontendFailure(systemId, name, message)
                        showPreparationFailure(name, message)
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (error: Throwable) {
                val message = preparationErrorMessage(error)
                persistFrontendFailure(systemId, name, message)
                runOnUiThread {
                    preparationInProgress = false
                    showPreparationFailure(name, message)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun persistFrontendFailure(systemId: String, gameName: String, message: String) {
        runCatching {
            val directory = File(filesDir, "saves/$systemId").apply { mkdirs() }
            File(directory, "last_core_diagnostics.txt").writeText(
                "Frontend preparation failed\nSystem: $systemId\nGame: $gameName\nError: $message\n",
            )
        }
    }

    private fun showPreparation(name: String) {
        preparationPanel.visibility = View.VISIBLE
        preparationTitle.text = "Preparing $name"
        preparationStatus.text = "Starting…"
        preparationProgress.visibility = View.VISIBLE
        preparationProgress.isIndeterminate = true
        preparationProgress.progress = 0
    }

    private fun showPreparationFailure(name: String, message: String) {
        preparationPanel.visibility = View.VISIBLE
        preparationTitle.text = "Could not start $name"
        preparationProgress.visibility = View.GONE
        preparationStatus.text = message
    }

    private fun updatePreparation(label: String, completed: Long, total: Long) {
        preparationPanel.visibility = View.VISIBLE
        preparationProgress.visibility = View.VISIBLE
        if (total > 0L) {
            val safeCompleted = completed.coerceIn(0L, total)
            val percent = ((safeCompleted * 100L) / total).toInt().coerceIn(0, 100)
            preparationProgress.isIndeterminate = false
            preparationProgress.progress = percent
            preparationStatus.text = "$label · $percent%"
        } else {
            preparationProgress.isIndeterminate = true
            preparationStatus.text = label
        }
    }

    private fun postPreparation(label: String, completed: Long, total: Long) {
        runOnUiThread {
            if (preparationInProgress) updatePreparation(label, completed, total)
        }
    }

    /** Throttle progress callbacks so a large image does not flood the main thread. */
    private fun createPreparationReporter(): (String, Long, Long) -> Unit {
        var lastUpdate = 0L
        return { label, completed, total ->
            val now = SystemClock.uptimeMillis()
            if (completed <= 0L || (total > 0L && completed >= total) || now - lastUpdate >= 200L) {
                lastUpdate = now
                postPreparation(label, completed, total)
            }
        }
    }

    private fun preparationErrorMessage(error: Throwable): String {
        val messages = mutableListOf<String>()
        var current: Throwable? = error
        repeat(5) {
            val message = current?.message?.trim().orEmpty()
            if (message.isNotEmpty() && message !in messages) messages += message
            current = current?.cause
        }
        return messages.joinToString(" → ").ifBlank { error.javaClass.simpleName }
    }

    private fun isReusablePreparedFile(file: File, originalName: String, systemId: String): Boolean {
        if (file.extension.lowercase() in setOf("gdi", "cue", "m3u")) {
            return runCatching { validateDiscSidecars(file, systemId) }.isSuccess
        }
        val originalExtension = originalName.substringAfterLast('.', "").lowercase()
        if (systemId == "dreamcast" && originalExtension in setOf("zip", "7z")) {
            // Older builds could cache track01.iso as the selected game even
            // when the archive also contained a GDI descriptor. Force those
            // ambiguous prepared copies through the corrected selector once.
            return when (file.extension.lowercase()) {
                "gdi", "cue", "m3u" -> runCatching { validateDiscSidecars(file, systemId) }.isSuccess
                "cdi", "chd", "lst", "elf" -> true
                else -> false
            }
        }
        return true
    }

    private fun prepareRom(
        uri: Uri,
        name: String,
        systemId: String,
        romDirectory: File,
        report: (String, Long, Long) -> Unit,
        companionDocuments: List<SelectedDocument> = emptyList(),
    ): File {
        val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val key = uri.toString().hashCode().toUInt().toString(16)
        if (companionDocuments.isNotEmpty() && name.substringAfterLast('.', "").lowercase() !in setOf("zip", "7z")) {
            return prepareLooseDiscSet(
                SelectedDocument(uri, name), companionDocuments, key, systemId, romDirectory, report,
            )
        }
        val source = File(romDirectory, "${key}_$safeName")
        val maxBytes = maxRomBytes(systemId)
        report("Checking file and storage", 0L, -1L)
        val sourceSize = querySize(uri)
        require(sourceSize <= 0L || sourceSize <= maxBytes) {
            "The selected game is too large for Android preparation (${formatBytes(maxBytes)} limit)."
        }
        val available = StatFs(romDirectory.absolutePath).availableBytes
        require(sourceSize <= 0L || available > sourceSize + MIN_FREE_SPACE_BYTES) {
            "Not enough free storage to prepare this game file."
        }
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Android could not open this game." }
            val copyLabel = if (name.substringAfterLast('.', "").lowercase() in setOf("zip", "7z")) {
                "Copying archive"
            } else {
                "Copying game file"
            }
            writeLimited(input, source, maxBytes, copyLabel, sourceSize, report)
        }
        return when (name.substringAfterLast('.', "").lowercase()) {
            "zip" -> extractZip(source, key, systemId, romDirectory, report)
            "7z" -> extractSevenZip(source, key, systemId, romDirectory, report)
            else -> source
        }
    }

    /** Copies a loose CUE/GDI set selected together into one core-readable directory. */
    private fun prepareLooseDiscSet(
        primary: SelectedDocument,
        companions: List<SelectedDocument>,
        key: String,
        systemId: String,
        outputDirectory: File,
        report: (String, Long, Long) -> Unit,
    ): File {
        val root = File(outputDirectory, "${key}_disc").apply { mkdirs() }
        val maxBytes = maxRomBytes(systemId)
        val documents = listOf(primary) + companions
        val expectedTotal = documents.sumOf { querySize(it.uri).coerceAtLeast(0L) }
            .takeIf { it > 0L } ?: -1L
        var completed = 0L
        var primaryFile: File? = null
        documents.forEach { document ->
            val leafName = document.name.substringAfterLast('/').substringAfterLast('\\')
                .replace('\u0000', '_').ifBlank { "disc_file" }
            val output = File(root, leafName)
            contentResolver.openInputStream(document.uri).use { input ->
                requireNotNull(input) { "Android could not open $leafName." }
                val written = writeLimited(
                    input,
                    output,
                    maxBytes,
                    "Copying $leafName",
                    querySize(document.uri),
                ) { _, fileCompleted, _ ->
                    report("Copying loose disc files", completed + fileCompleted, expectedTotal)
                }
                completed += written
            }
            if (document.uri == primary.uri) primaryFile = output
        }
        report("Copying loose disc files", completed, expectedTotal)
        return requireNotNull(primaryFile) { "The selected disc descriptor could not be prepared." }
    }

    private fun validatePreparedRom(rom: File, systemId: String) {
        when (systemId) {
            "n64" -> FileInputStream(rom).use { input ->
                val header = ByteArray(4)
                require(input.read(header) == header.size) { "The selected file is too small to be an N64 ROM." }
                val validHeader = header.contentEquals(byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40)) ||
                    header.contentEquals(byteArrayOf(0x37, 0x80.toByte(), 0x40, 0x12)) ||
                    header.contentEquals(byteArrayOf(0x40, 0x12, 0x37, 0x80.toByte()))
                require(validHeader) { "The selected file does not have a valid N64 ROM header." }
            }
            "gamecube" -> validateGameCubeDisc(rom)
            "dreamcast", "ps1", "ps2" -> validateDiscSidecars(rom, systemId)
        }
    }

    /**
     * Descriptor files are harmless to copy, but a core that receives a GDI,
     * CUE, or M3U without its referenced tracks can dereference an invalid
     * path in native code. Direct Android document picks only contain the one
     * selected file, so fail early and direct the user to an archive that
     * contains the complete disc set.
     */
    private fun validateDiscSidecars(rom: File, systemId: String) {
        val extension = rom.extension.lowercase()
        when (extension) {
            "gdi" -> {
                val lines = rom.readLines()
                val trackCount = lines.firstOrNull()?.trim()?.removePrefix("\uFEFF")?.toIntOrNull()
                require(trackCount != null && trackCount > 0) { "This Dreamcast GDI is missing its track list." }
                require(lines.size >= trackCount + 1) { "This Dreamcast GDI is missing track entries." }
                lines.drop(1).take(trackCount).forEach { line ->
                    val quoted = Regex("\\\"([^\\\"]+)\\\"").find(line)?.groupValues?.getOrNull(1)
                    val referenced = quoted ?: line.trim().split(Regex("\\s+")).getOrNull(4)
                    require(!referenced.isNullOrBlank()) { "This Dreamcast GDI has an invalid track entry." }
                    requireSidecar(rom, referenced, "Dreamcast GDI")
                }
            }
            "cue" -> {
                val cueFile = Regex("^\\s*FILE\\s+(?:\"([^\"]+)\"|'([^']+)'|(\\S+))", RegexOption.IGNORE_CASE)
                val files = rom.readLines().mapNotNull { line ->
                    val match = cueFile.find(line) ?: return@mapNotNull null
                    match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
                }
                require(files.isNotEmpty()) { "This ${systemId.uppercase()} CUE has no track files." }
                files.forEach { referenced -> requireSidecar(rom, referenced, "${systemId.uppercase()} CUE") }
            }
            "m3u" -> {
                val entries = rom.readLines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
                require(entries.isNotEmpty()) { "This ${systemId.uppercase()} playlist is empty." }
                entries.forEach { requireSidecar(rom, it, "${systemId.uppercase()} playlist") }
            }
        }
    }

    private fun requireSidecar(descriptor: File, reference: String, format: String) {
        val clean = reference.trim().trim('"', '\'').replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString(File.separator)
        val sidecar = findSidecar(descriptor.parentFile ?: File("."), clean)
        require(sidecar.isFile && sidecar.length() > 0L) {
            "$format cannot access track file ${reference.substringAfterLast('/')} — select the descriptor and all tracks together, or choose a ZIP/7z containing the complete disc set."
        }
    }

    /** Resolves archive sidecars without requiring a case-sensitive filename match. */
    private fun findSidecar(parent: File, relativePath: String): File {
        var current = parent
        relativePath.split(File.separatorChar).filter { it.isNotBlank() }.forEach { part ->
            val direct = File(current, part)
            current = when {
                direct.isFile || direct.isDirectory -> direct
                else -> current.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: File(current, part)
            }
        }
        return current
    }

    private fun validateGameCubeDisc(rom: File) {
        if (rom.extension.lowercase() !in setOf("iso", "gcm")) return
        require(rom.length() >= 0x20) { "The selected GameCube image is too small to be a disc." }
        FileInputStream(rom).use { input ->
            val header = ByteArray(0x20)
            require(input.read(header) == header.size) { "The selected GameCube image is truncated." }
            val gameCubeMagic = header.copyOfRange(0x1c, 0x20)
            val wiiMagic = header.copyOfRange(0x18, 0x1c)
            val valid = gameCubeMagic.contentEquals(byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D)) ||
                wiiMagic.contentEquals(byteArrayOf(0x5D, 0x1C, 0x9E.toByte(), 0xA3.toByte()))
            require(valid) { "The selected file is not a valid GameCube/Wii disc image." }
        }
    }

    private fun extractZip(
        source: File,
        key: String,
        systemId: String,
        outputDirectory: File,
        report: (String, Long, Long) -> Unit,
    ): File {
        val extractionRoot = File(outputDirectory, "${key}_archive").apply { mkdirs() }
        val maxBytes = maxRomBytes(systemId)
        var selected: File? = null
        ZipInputStream(BufferedInputStream(FileInputStream(source))).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (!entry.isDirectory && shouldExtractArchiveEntry(entry.name, systemId)) {
                    val output = File(extractionRoot, safeRelativeEntryName(entry.name)).apply {
                        parentFile?.mkdirs()
                    }
                    val entryName = entry.name.substringAfterLast('/').ifBlank { entry.name }
                    report("Extracting $entryName", 0L, entry.size.takeIf { it > 0L } ?: -1L)
                    writeLimited(
                        archive,
                        output,
                        maxBytes,
                        "Extracting $entryName",
                        entry.size.takeIf { it > 0L } ?: -1L,
                        report,
                    )
                    if (selected == null || archiveEntryPriority(entry.name, systemId) < archiveEntryPriority(selected!!.name, systemId)) {
                        selected = output
                    }
                }
            }
        }
        selected?.let { return it }
        throw IllegalArgumentException("No ${Systems.byId(systemId)?.shortName ?: systemId} game was found inside the ZIP.")
    }

    private fun extractSevenZip(
        source: File,
        key: String,
        systemId: String,
        outputDirectory: File,
        report: (String, Long, Long) -> Unit,
    ): File {
        val extractionRoot = File(outputDirectory, "${key}_archive").apply { mkdirs() }
        val maxBytes = maxRomBytes(systemId)
        var selected: File? = null
        SevenZFile(source).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (!entry.isDirectory && shouldExtractArchiveEntry(entry.name, systemId)) {
                    require(entry.size <= maxBytes) { "The selected ROM is too large." }
                    val output = File(extractionRoot, safeRelativeEntryName(entry.name)).apply {
                        parentFile?.mkdirs()
                    }
                    val entryName = entry.name.substringAfterLast('/').ifBlank { entry.name }
                    val entrySize = entry.size.takeIf { it > 0L } ?: -1L
                    report("Extracting $entryName", 0L, entrySize)
                    FileOutputStream(output).use { destination ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = archive.read(buffer)
                            if (read <= 0) break
                            total += read
                            require(total <= maxBytes) { "The selected ROM is too large." }
                            destination.write(buffer, 0, read)
                            report("Extracting $entryName", total, entrySize)
                        }
                        report("Extracting $entryName", total, entrySize)
                    }
                    if (selected == null || archiveEntryPriority(entry.name, systemId) < archiveEntryPriority(selected!!.name, systemId)) {
                        selected = output
                    }
                }
            }
        }
        selected?.let { return it }
        throw IllegalArgumentException("No ${Systems.byId(systemId)?.shortName ?: systemId} game was found inside the 7z archive.")
    }

    private fun writeLimited(
        input: java.io.InputStream,
        output: File,
        maxBytes: Long = MAX_ROM_BYTES,
        label: String = "Copying game file",
        expectedBytes: Long = -1L,
        report: (String, Long, Long) -> Unit = { _, _, _ -> },
    ): Long {
        FileOutputStream(output).use { destination ->
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            report(label, 0L, expectedBytes)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                require(total <= maxBytes) { "The selected ROM is too large." }
                destination.write(buffer, 0, read)
                report(label, total, expectedBytes)
            }
            report(label, total, expectedBytes)
            return total
        }
    }

    private fun querySize(uri: Uri): Long = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst() && sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else -1L
    } ?: -1L

    private fun maxRomBytes(systemId: String): Long = when (systemId) {
        // PCSX-ReARMed was working with full-size disc images before the
        // preparation progress UI was introduced. PS1 accidentally fell
        // through to the 128 MB cartridge guard in that refactor. It has no
        // frontend-imposed size ceiling; the real constraint is free storage.
        "ps1" -> Long.MAX_VALUE
        "gamecube", "dreamcast", "psp", "ps2" -> MAX_DISC_IMAGE_BYTES
        else -> MAX_ROM_BYTES
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
        else -> "${bytes / (1024L * 1024L)} MB"
    }

    private fun isSupportedEntry(name: String, systemId: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in (Systems.byId(systemId)?.extensions ?: emptySet()) && extension !in setOf("zip", "7z")
    }

    private fun shouldExtractArchiveEntry(name: String, systemId: String): Boolean {
        if (isSupportedEntry(name, systemId)) return true
        if (systemId != "dreamcast") return false
        // GDI/CUE descriptors often reference raw or WAV tracks that are not
        // themselves launchable entries. Keep them beside the descriptor so
        // Flycast can resolve the relative paths after extraction.
        return name.substringAfterLast('.', "").lowercase() in setOf(
            "raw", "track", "wav", "flac", "ogg", "mp3", "ape", "ecm",
            "sub", "img", "toc", "dat", "idx", "iso",
        )
    }

    /** Keeps disc-image sidecar files next to their descriptor (e.g. GDI + tracks). */
    private fun safeRelativeEntryName(name: String): String = name
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() && it != "." && it != ".." }
        // Preserve the archive's original filename.  GDI/CUE descriptors
        // refer to track names verbatim, so replacing punctuation here makes
        // a track appear to be missing even though it was in the archive.
        .joinToString("/") { it.replace('\u0000', '_') }
        .ifBlank { "entry" }

    private fun archiveEntryPriority(name: String, systemId: String): Int {
        val extension = name.substringAfterLast('.', "").lowercase()
        // In a Dreamcast archive, .iso/.bin files are commonly tracks owned
        // by a GDI/CUE descriptor, not independently bootable games. Giving
        // every supported extension equal priority could launch track01.iso
        // simply because it appeared before game.gdi in the ZIP directory.
        if (systemId == "dreamcast") return when (extension) {
            "gdi", "cue", "m3u" -> 0
            "cdi", "chd", "lst", "elf" -> 1
            else -> 10
        }
        return when (extension) {
            "cue", "m3u" -> 0
            "lst", "elf", "iso", "chd", "cdi", "cso", "pbp", "z64", "n64", "v64" -> 1
            else -> 10
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "game"
    }

    private fun biosDirectory(systemId: String) = File(filesDir, "bios/$systemId")

    private fun systemDirectoryFor(
        systemId: String,
        report: (String, Long, Long) -> Unit,
    ): String {
        if (systemId == "dreamcast") {
            ensureBundledDreamcastBios()
            return biosDirectory(systemId).absolutePath
        }
        val systemRoot = File(filesDir, "systems/$systemId").apply { mkdirs() }
        if (systemId == "gamecube") ensureBundledDolphinSystemFiles(systemRoot, report)
        return systemRoot.absolutePath
    }

    /** Copies the repository-provided Dreamcast BIOS into Flycast's expected layout. */
    private fun ensureBundledDreamcastBios() {
        val targetDirectory = File(biosDirectory("dreamcast"), "dc").apply { mkdirs() }
        listOf("dc_boot.bin", "dc_flash.bin").forEach { name ->
            val target = File(targetDirectory, name)
            assets.open("dreamcast/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
            }
        }
    }

    /** Installs the official libretro Dolphin runtime data on first launch. */
    private fun ensureBundledDolphinSystemFiles(
        systemRoot: File,
        report: (String, Long, Long) -> Unit,
    ) {
        val codeHandler = File(systemRoot, "dolphin-emu/Sys/codehandler.bin")
        val marker = File(systemRoot, ".dolphin-system-$DOLPHIN_SYSTEM_ASSET_VERSION")
        if (marker.isFile && codeHandler.isFile && codeHandler.length() > 0L) return

        report("Installing Dolphin system files", 0L, -1L)
        assets.open("dolphin/Dolphin.zip").use { source ->
            ZipInputStream(BufferedInputStream(source)).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val relative = safeRelativeEntryName(entry.name)
                    val output = File(systemRoot, relative)
                    require(output.canonicalPath.startsWith(systemRoot.canonicalPath + File.separator)) {
                        "The bundled Dolphin system archive contains an invalid path."
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { destination -> archive.copyTo(destination, BUFFER_SIZE) }
                    }
                    archive.closeEntry()
                }
            }
        }
        require(codeHandler.isFile && codeHandler.length() > 0L) {
            "Dolphin system files could not be installed (codehandler.bin is missing)."
        }
        marker.writeText(DOLPHIN_SYSTEM_ASSET_VERSION)
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
        override fun getCount() = Systems.all.size + 1
        override fun getItem(position: Int): SystemDefinition? = if (position == 0) null else Systems.all[position - 1]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?) =
            navigationLabel(convertView, position, false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?) =
            navigationLabel(convertView, position, true)
        private fun navigationLabel(convertView: View?, position: Int, dropdown: Boolean): TextView {
            if (position == 0) {
                return (convertView as? TextView ?: TextView(this@MainActivity)).apply {
                    text = "HOME  ·  EmuAll"
                    textSize = if (dropdown) 14f else 15f
                    setTextColor(getColor(if (dropdown) R.color.accent else R.color.text_primary))
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(if (dropdown) 12 else 8), dp(14), dp(if (dropdown) 12 else 8))
                    setBackgroundColor(getColor(if (dropdown) R.color.panel_raised else android.R.color.transparent))
                }
            }
            return systemLabel(convertView, Systems.all[position - 1], dropdown)
        }
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
        private const val HOME_RECENT_LIMIT = 8
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_ROM_BYTES = 128L * 1024L * 1024L
        private const val MAX_DISC_IMAGE_BYTES = 16L * 1024L * 1024L * 1024L
        private const val MIN_FREE_SPACE_BYTES = 128L * 1024L * 1024L
        private const val DOLPHIN_SYSTEM_ASSET_VERSION = "2026-09-10"
    }
}
