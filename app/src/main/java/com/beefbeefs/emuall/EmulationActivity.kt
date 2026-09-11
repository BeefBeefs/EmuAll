package com.beefbeefs.emuall

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class EmulationActivity : AppCompatActivity() {
    private lateinit var surface: GameSurfaceView
    private lateinit var surfaceHost: FrameLayout
    private var controllerMapping: Map<Int, Int> = ControllerMappingStore.defaultMapping()
    // Rotation can destroy/recreate a window while Android reports the old
    // Activity as finishing. Only an explicit user exit is allowed to stop
    // the native session; configuration changes must leave it alive.
    private var explicitExit = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_emulation)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        surface = findViewById(R.id.gameSurface)
        surfaceHost = findViewById(R.id.surfaceHost)
        applySessionLayout(resources.configuration.orientation)
        val rom = intent.getStringExtra(EXTRA_ROM) ?: return finish()
        val save = intent.getStringExtra(EXTRA_SAVE) ?: return finish()
        val coreLibrary = intent.getStringExtra(EXTRA_CORE_LIBRARY) ?: "libmgba_libretro.so"
        val coreName = intent.getStringExtra(EXTRA_CORE_NAME) ?: "libretro core"
        val systemId = intent.getStringExtra(EXTRA_SYSTEM_ID) ?: Systems.all.first().id
        val core = CoreRegistry.forSystem(systemId)
        val videoBackend = core?.let { GraphicsBackendSelector.select(this, it) } ?: VideoBackend.OPENGL_ES
        controllerMapping = ControllerMappingStore(this).mappingFor(systemId)
        val coreLoadError = runCatching { NativeCoreBridge.ensureCoreLoaded(coreLibrary) }.exceptionOrNull()
        if (coreLoadError != null) {
            findViewById<TextView>(R.id.sessionStatus).text = "Could not load $coreName: ${coreLoadError.message ?: coreLoadError.javaClass.simpleName}"
            finish()
            return
        }
        bindControls()
        val systemDirectory = intent.getStringExtra(EXTRA_SYSTEM_DIRECTORY) ?: filesDir.absolutePath
        surface.start(coreLibrary, rom, save, systemDirectory, coreName, videoBackend, core?.requiresHardwareRendering == true) { status ->
            findViewById<TextView>(R.id.sessionStatus).text = status
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_LOAD, false)) surface.quickLoad(intent.getIntExtra(EXTRA_AUTO_LOAD_SLOT, 1))
        findViewById<Button>(R.id.menuButton).setOnClickListener {
            explicitExit = true
            finish()
        }
        findViewById<Button>(R.id.pauseButton).setOnClickListener { button ->
            surface.setPaused(!surface.isPaused()); (button as Button).text = if (surface.isPaused()) "Resume" else "Pause"
        }
        findViewById<Button>(R.id.resetButton).setOnClickListener { surface.resetGame() }
        findViewById<Button>(R.id.quickSaveButton).apply {
            setOnClickListener { showStateMenu(this, true) }
        }
        findViewById<Button>(R.id.quickLoadButton).apply {
            setOnClickListener { showStateMenu(this, false) }
        }
        findViewById<Button>(R.id.fastButton).setOnClickListener { button -> (button as Button).text = if (surface.toggleFastForward()) "FF 3×" else "FF" }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the GLSurfaceView in the same host for the entire session. A
        // live EGL context must not be paused or reparented while a libretro
        // core is running; doing that leaves most hardware cores drawing into
        // a destroyed surface after the rotation.
        applySessionLayout(newConfig.orientation)
        surface.post { surface.onLayoutChanged() }
    }

    private fun applySessionLayout(orientation: Int) {
        val controls = findViewById<FrameLayout>(R.id.gameControls)
        val directionalPad = findViewById<View>(R.id.directionalPad)
        val actionButtons = findViewById<View>(R.id.actionButtons)
        val centerButtons = findViewById<View>(R.id.centerButtons)

        // Keep both the GLSurfaceView host and the control overlay attached to
        // the same permanent FrameLayout. Reparenting a SurfaceView while the
        // phone returns from landscape can destroy its window and end the
        // active libretro session even when the Activity itself survives.
        controls.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            surfaceHost.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = dp(176)
                rightMargin = dp(184)
            }
            directionalPad.layoutParams = FrameLayout.LayoutParams(dp(156), dp(156)).apply {
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                leftMargin = dp(10)
            }
            actionButtons.layoutParams = FrameLayout.LayoutParams(dp(178), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                rightMargin = dp(4)
            }
            centerButtons.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
                rightMargin = dp(18)
                bottomMargin = dp(14)
            }
        } else {
            surfaceHost.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                bottomMargin = dp(240)
            }
            directionalPad.layoutParams = FrameLayout.LayoutParams(dp(156), dp(156)).apply {
                gravity = android.view.Gravity.START or android.view.Gravity.BOTTOM
                leftMargin = dp(12)
                bottomMargin = dp(42)
            }
            actionButtons.layoutParams = FrameLayout.LayoutParams(dp(170), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
                rightMargin = dp(12)
                bottomMargin = dp(36)
            }
            centerButtons.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                bottomMargin = dp(8)
            }
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
            .forEach { (viewId, buttonId) -> findViewById<Button>(viewId).setOnTouchListener { view, event ->
                val button = view as Button
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        button.isPressed = true
                        surface.setButton(buttonId, true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                        button.isPressed = false
                        surface.setButton(buttonId, false)
                    }
                }
                true
            } }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val id = controllerMapping[event.keyCode]
        if (id != null) { surface.setButton(id, event.action == KeyEvent.ACTION_DOWN); return true }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Use the system back dispatcher in a future Activity Result migration")
    override fun onBackPressed() {
        explicitExit = true
        super.onBackPressed()
    }

    override fun onDestroy() {
        // Do not infer an emulation exit from Activity destruction. During a
        // rotation Android may tear down the old window and report it as
        // finishing even though the user is still in the same session.
        if (explicitExit) surface.stop()
        super.onDestroy()
    }
    companion object {
        const val EXTRA_ROM = "rom"
        const val EXTRA_SAVE = "save"
        const val EXTRA_CORE_LIBRARY = "core_library"
        const val EXTRA_CORE_NAME = "core_name"
        const val EXTRA_SYSTEM_ID = "system_id"
        const val EXTRA_SYSTEM_DIRECTORY = "system_directory"
        const val EXTRA_AUTO_LOAD = "auto_load"
        const val EXTRA_AUTO_LOAD_SLOT = "auto_load_slot"
    }
}
