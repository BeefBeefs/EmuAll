package com.beefbeefs.emuall

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var recentStore: RecentGameStore
    private lateinit var recentSection: LinearLayout
    private var pendingSystem: SystemDefinition? = null
    private val deviceGlesVersion by lazy {
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.reqGlEsVersion
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        recentStore = RecentGameStore(this)
        recentSection = findViewById(R.id.recentSection)

        val nativeStatus = findViewById<TextView>(R.id.nativeStatus)
        nativeStatus.text = runCatching {
            "${NativeCoreBridge.frontendVersion()} · OpenGL ES renderer ready"
        }.getOrElse { "Native frontend could not load: ${it.javaClass.simpleName}" }

        populateSystems(findViewById(R.id.systemGrid))
        populateRecents()
    }

    private fun populateSystems(grid: GridLayout) {
        Systems.all.forEachIndexed { index, system ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(12), dp(14))
                background = getDrawable(R.drawable.panel)
                isClickable = true
                isFocusable = true
                foreground = selectableItemBackground()
                contentDescription = "Open ${system.displayName}"
                addView(label(system.shortName, 17f, R.color.text_primary, true))
                addView(label(system.coreName, 11f, R.color.text_muted, false).apply {
                    setPadding(0, dp(5), 0, 0)
                })
                addView(label(system.integrationTier.label, 10f, if (supports(system)) R.color.accent else R.color.orange, false).apply {
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { showSystem(system) }
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / 2, 1f),
                GridLayout.spec(index % 2, 1f),
            ).apply {
                width = 0
                height = dp(92)
                setMargins(if (index % 2 == 0) 0 else dp(5), dp(5), if (index % 2 == 0) dp(5) else 0, dp(5))
            }
            grid.addView(card, params)
        }
    }

    private fun showSystem(system: SystemDefinition) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_system, null)
        view.findViewById<TextView>(R.id.systemKicker).text = "${system.shortName} · NATIVE CORE"
        view.findViewById<TextView>(R.id.systemTitle).text = system.displayName
        view.findViewById<TextView>(R.id.coreName).text = system.coreName
        val bios = if (system.biosRequired) "\nA legally obtained BIOS is required." else ""
        val hardware = if (system.minimumGles > 0x00020000) {
            "\nRequires OpenGL ES ${glesName(system.minimumGles)} or a future Vulkan backend."
        } else ""
        val unavailable = if (system.integrationTier == IntegrationTier.FUTURE) {
            "\nThis system will activate only after a usable Android core is validated."
        } else ""
        view.findViewById<TextView>(R.id.systemFormats).text =
            if (system.extensions.isEmpty()) "$unavailable$hardware" else
                "Recognized files: ${system.extensions.joinToString { ".$it" }}$bios$hardware$unavailable"
        val chooseButton = view.findViewById<Button>(R.id.chooseGameButton)
        chooseButton.isEnabled = supports(system) && system.integrationTier != IntegrationTier.FUTURE
        chooseButton.text = when {
            system.integrationTier == IntegrationTier.FUTURE -> "Android Core Not Yet Available"
            !supports(system) -> "Requires OpenGL ES ${glesName(system.minimumGles)}"
            else -> "Choose Game"
        }
        chooseButton.setOnClickListener {
            pendingSystem = system
            dialog.dismiss()
            openGamePicker()
        }
        view.findViewById<Button>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
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
        val system = pendingSystem ?: return
        val name = displayName(uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in system.extensions) {
            Toast.makeText(this, "$name is not recognized as a ${system.shortName} game.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        recentStore.add(RecentGame(system.id, name, uri, System.currentTimeMillis()))
        populateRecents()
        Toast.makeText(this, "$name added. ${system.coreName} integration is the next native milestone.", Toast.LENGTH_LONG).show()
    }

    private fun populateRecents() {
        recentSection.removeAllViews()
        val games = recentStore.load()
        recentSection.addView(label("RECENTLY PLAYED", 17f, R.color.text_primary, true))
        if (games.isEmpty()) {
            recentSection.addView(label("Games you choose will appear here per system.", 13f, R.color.text_muted, false).apply {
                setPadding(0, dp(10), 0, dp(10))
            })
            return
        }
        games.forEach { game ->
            val system = Systems.byId(game.systemId) ?: return@forEach
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = getDrawable(R.drawable.panel)
                addView(label(game.name, 14f, R.color.text_primary, true))
                addView(label("${system.shortName} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(game.playedAt))}", 11f, R.color.text_muted, false).apply {
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { showSystem(system) }
            }
            recentSection.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(9)
            })
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "game"
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(getColor(color))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 2
    }

    private fun selectableItemBackground() = android.util.TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        getDrawable(value.resourceId)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun supports(system: SystemDefinition) = deviceGlesVersion >= system.minimumGles

    private fun glesName(version: Int) = "${version shr 16}.${version and 0xffff}"

    companion object {
        private const val PICK_GAME = 1001
    }
}
