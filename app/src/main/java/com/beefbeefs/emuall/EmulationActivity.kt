package com.beefbeefs.emuall

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class EmulationActivity : AppCompatActivity() {
    private lateinit var surface: GameSurfaceView
    private var controllerMapping: Map<Int, Int> = ControllerMappingStore.defaultMapping()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_emulation)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        surface = findViewById(R.id.gameSurface)
        applySessionLayout(resources.configuration.orientation)
        val rom = intent.getStringExtra(EXTRA_ROM) ?: return finish()
        val save = intent.getStringExtra(EXTRA_SAVE) ?: return finish()
        val coreLibrary = intent.getStringExtra(EXTRA_CORE_LIBRARY) ?: "libmgba_libretro.so"
        val coreName = intent.getStringExtra(EXTRA_CORE_NAME) ?: "libretro core"
        val systemId = intent.getStringExtra(EXTRA_SYSTEM_ID) ?: Systems.all.first().id
        controllerMapping = ControllerMappingStore(this).mappingFor(systemId)
        val coreLoadError = runCatching { NativeCoreBridge.ensureCoreLoaded(coreLibrary) }.exceptionOrNull()
        if (coreLoadError != null) {
            findViewById<TextView>(R.id.sessionStatus).text = "Could not load $coreName: ${coreLoadError.message ?: coreLoadError.javaClass.simpleName}"
            finish()
            return
        }
        bindControls()
        surface.start(coreLibrary, rom, save, filesDir.absolutePath, coreName) { status ->
            findViewById<TextView>(R.id.sessionStatus).text = status
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_LOAD, false)) surface.quickLoad(intent.getIntExtra(EXTRA_AUTO_LOAD_SLOT, 1))
        findViewById<Button>(R.id.menuButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.pauseButton).setOnClickListener { button ->
            surface.setPaused(!surface.isPaused()); (button as Button).text = if (surface.isPaused()) "Resume" else "Pause"
        }
        findViewById<Button>(R.id.resetButton).setOnClickListener { surface.resetGame() }
        findViewById<Button>(R.id.quickSaveButton).apply {
            setOnClickListener { surface.quickSave() }
            setOnLongClickListener { showStateMenu(this, true); true }
        }
        findViewById<Button>(R.id.quickLoadButton).apply {
            setOnClickListener { surface.quickLoad() }
            setOnLongClickListener { showStateMenu(this, false); true }
        }
        findViewById<Button>(R.id.fastButton).setOnClickListener { button -> (button as Button).text = if (surface.toggleFastForward()) "FF 3×" else "FF" }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySessionLayout(newConfig.orientation)
    }

    private fun applySessionLayout(orientation: Int) {
        val body = findViewById<LinearLayout>(R.id.sessionBody)
        val controls = findViewById<FrameLayout>(R.id.gameControls)
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            body.orientation = LinearLayout.HORIZONTAL
            surface.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            controls.layoutParams = LinearLayout.LayoutParams(dp(360), LinearLayout.LayoutParams.MATCH_PARENT)
        } else {
            body.orientation = LinearLayout.VERTICAL
            surface.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            controls.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showStateMenu(anchor: View, save: Boolean) {
        PopupMenu(this, anchor).apply {
            (1..3).forEach { slot -> menu.add(Menu.NONE, slot, slot, "${if (save) "Save" else "Load"} Slot $slot") }
            setOnMenuItemClickListener { item ->
                if (save) surface.quickSave(item.itemId) else surface.quickLoad(item.itemId)
                true
            }
        }.show()
    }
    private fun bindControls() {
        mapOf(R.id.upButton to 4, R.id.downButton to 5, R.id.leftButton to 6, R.id.rightButton to 7,
            R.id.aButton to 8, R.id.bButton to 0, R.id.lButton to 10, R.id.rButton to 11, R.id.selectButton to 2, R.id.startButton to 3)
            .forEach { (viewId, buttonId) -> findViewById<Button>(viewId).setOnTouchListener { _, event ->
                when (event.actionMasked) { MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> surface.setButton(buttonId, true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> surface.setButton(buttonId, false) }; true } }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val id = controllerMapping[event.keyCode]
        if (id != null) { surface.setButton(id, event.action == KeyEvent.ACTION_DOWN); return true }
        return super.dispatchKeyEvent(event)
    }
    override fun onDestroy() { surface.stop(); super.onDestroy() }
    companion object {
        const val EXTRA_ROM = "rom"
        const val EXTRA_SAVE = "save"
        const val EXTRA_CORE_LIBRARY = "core_library"
        const val EXTRA_CORE_NAME = "core_name"
        const val EXTRA_SYSTEM_ID = "system_id"
        const val EXTRA_AUTO_LOAD = "auto_load"
        const val EXTRA_AUTO_LOAD_SLOT = "auto_load_slot"
    }
}
