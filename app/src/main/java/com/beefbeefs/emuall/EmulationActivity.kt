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
    private lateinit var surfaceHost: FrameLayout
    private var controllerMapping: Map<Int, Int> = ControllerMappingStore.defaultMapping()
    private var landscapeLeft: LinearLayout? = null
    private var landscapeRight: LinearLayout? = null
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
        findViewById<Button>(R.id.menuButton).setOnClickListener { finish() }
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
        val body = findViewById<LinearLayout>(R.id.sessionBody)
        val controls = findViewById<FrameLayout>(R.id.gameControls)
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (landscapeLeft == null) {
                val directionalPad = findViewById<View>(R.id.directionalPad)
                val actionButtons = findViewById<View>(R.id.actionButtons)
                val centerButtons = findViewById<View>(R.id.centerButtons)
                controls.removeView(directionalPad)
                controls.removeView(actionButtons)
                controls.removeView(centerButtons)
                body.removeView(controls)

                val left = LinearLayout(this).apply {
                    this.orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    addView(directionalPad, LinearLayout.LayoutParams(dp(164), dp(164)))
                }
                val right = LinearLayout(this).apply {
                    this.orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    addView(actionButtons, LinearLayout.LayoutParams(dp(178), LinearLayout.LayoutParams.WRAP_CONTENT))
                    addView(centerButtons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(12)
                    })
                }
                landscapeLeft = left
                landscapeRight = right
                body.orientation = LinearLayout.HORIZONTAL
                // Keep surfaceHost (and therefore GLSurfaceView) attached to
                // the window. Only the non-GL control wrappers are inserted
                // around it; detaching an ancestor of a live GLSurfaceView
                // can still tear down its EGL surface during rotation.
                body.addView(left, 0, LinearLayout.LayoutParams(dp(176), LinearLayout.LayoutParams.MATCH_PARENT))
                val surfaceIndex = body.indexOfChild(surfaceHost)
                body.addView(right, surfaceIndex + 1, LinearLayout.LayoutParams(dp(184), LinearLayout.LayoutParams.MATCH_PARENT))
                surfaceHost.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }
        } else {
            body.orientation = LinearLayout.VERTICAL
            landscapeLeft?.let { body.removeView(it) }
            landscapeRight?.let { body.removeView(it) }
            if (landscapeLeft != null) {
                val directionalPad = findViewById<View>(R.id.directionalPad)
                val actionButtons = findViewById<View>(R.id.actionButtons)
                val centerButtons = findViewById<View>(R.id.centerButtons)
                landscapeLeft?.removeView(directionalPad)
                landscapeRight?.removeView(actionButtons)
                landscapeRight?.removeView(centerButtons)
                controls.addView(directionalPad, FrameLayout.LayoutParams(dp(156), dp(156)).apply {
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                })
                controls.addView(actionButtons, FrameLayout.LayoutParams(dp(170), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                })
                controls.addView(centerButtons, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                })
                val surfaceIndex = body.indexOfChild(surfaceHost)
                body.addView(controls, surfaceIndex + 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)))
                surfaceHost.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                landscapeLeft = null
                landscapeRight = null
            } else {
                surfaceHost.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                controls.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240))
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
    override fun onDestroy() {
        // Android may destroy/recreate an Activity as part of a configuration
        // transition even when the manifest handles the common rotation
        // flags. Stopping the native core here would turn that transient
        // window teardown into a permanent end-of-emulation. The session is
        // stopped only when the user actually leaves the emulation screen.
        // A configuration transition can still report isChangingConfigurations
        // as false on some Android window-manager paths. Only stop when this
        // Activity is actually finishing; otherwise the native session must
        // remain available to the new orientation/window.
        if (isFinishing && !isChangingConfigurations) surface.stop()
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
