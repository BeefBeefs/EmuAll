package com.beefbeefs.emuall

import android.content.res.Configuration
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class EmulationActivity : AppCompatActivity() {
    private lateinit var surface: GameSurfaceView
    private lateinit var surfaceHost: FrameLayout
    private lateinit var systemId: String
    private val controlLayoutStore by lazy { VirtualControlLayoutStore(this) }
    private var controllerMapping: Map<Int, Int> = ControllerMappingStore.defaultMapping()
    private var editingControls = false
    private var wasPausedBeforeControlEdit = false
    private var layoutOrientation = Configuration.ORIENTATION_UNDEFINED
    private var draggedControl: View? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private val controlPlaceholders = mutableMapOf<Int, View>()
    private var individualControlsReady = false
    private var lastSessionStatus = "Starting core…"
    // Rotation can destroy/recreate a window while Android reports the old
    // Activity as finishing. Only an explicit user exit is allowed to stop
    // the native session; configuration changes must leave it alive.
    private var explicitExit = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_emulation)
        if (AppPreferences.keepScreenOn(this)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        surface = findViewById(R.id.gameSurface)
        surfaceHost = findViewById(R.id.surfaceHost)
        systemId = intent.getStringExtra(EXTRA_SYSTEM_ID) ?: Systems.all.first().id
        configureControlProfile()
        layoutOrientation = resources.configuration.orientation
        applySessionLayout(layoutOrientation)
        val rom = intent.getStringExtra(EXTRA_ROM)
        if (rom.isNullOrBlank()) {
            showLaunchError("Could not start emulation: no prepared game file was provided.")
            return
        }
        val save = intent.getStringExtra(EXTRA_SAVE)
        if (save.isNullOrBlank()) {
            showLaunchError("Could not start emulation: no save location was provided.")
            return
        }
        val coreLibrary = intent.getStringExtra(EXTRA_CORE_LIBRARY) ?: "libmgba_libretro.so"
        val coreName = intent.getStringExtra(EXTRA_CORE_NAME) ?: "libretro core"
        val core = CoreRegistry.forSystem(systemId)
        val videoBackend = core?.let { GraphicsBackendSelector.select(this, it) } ?: VideoBackend.OPENGL_ES
        controllerMapping = ControllerMappingStore(this).mappingFor(systemId)
        // Pass an absolute path so dlopen can keep each core RTLD_LOCAL.  The
        // old System.loadLibrary preloaded cores globally; large static
        // libraries such as Dolphin and Flycast then interposed one another's
        // symbols after switching systems in the same app process.
        val corePath = File(applicationInfo.nativeLibraryDir, coreLibrary)
        if (!corePath.isFile) {
            showLaunchError("Could not load $coreName: ${corePath.name} is not packaged for this device")
            return
        }
        val systemDirectory = intent.getStringExtra(EXTRA_SYSTEM_DIRECTORY) ?: filesDir.absolutePath
        surface.start(corePath.absolutePath, rom, save, systemDirectory, coreName, videoBackend, core?.requiresHardwareRendering == true) { status ->
            runOnUiThread {
                lastSessionStatus = status
                if (!editingControls) findViewById<TextView>(R.id.sessionStatus).text = status
            }
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_LOAD, false)) surface.quickLoad(intent.getIntExtra(EXTRA_AUTO_LOAD_SLOT, 1))
        findViewById<Button>(R.id.menuButton).setOnClickListener {
            if (editingControls) leaveControlEditMode(save = true)
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
        findViewById<Button>(R.id.editControlsButton).setOnClickListener {
            if (editingControls) leaveControlEditMode(save = true) else enterControlEditMode()
        }
        findViewById<TextView>(R.id.sessionStatus).setOnClickListener { showDiagnostics() }
    }

    private fun showLaunchError(message: String) {
        findViewById<TextView>(R.id.sessionStatus).text = message
        findViewById<FrameLayout>(R.id.gameControls).alpha = 0.45f
        findViewById<Button>(R.id.menuButton).apply {
            text = "Back"
            setOnClickListener {
                explicitExit = true
                finish()
            }
        }
    }

    private fun showDiagnostics() {
        val report = NativeCoreBridge.diagnostics().ifBlank {
            "No native diagnostics have been recorded yet."
        }
        val text = TextView(this).apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            this.text = report
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle("Emulation diagnostics")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("EmuAll diagnostics", report))
                Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the GLSurfaceView in the same host for the entire session. A
        // live EGL context must not be paused or reparented while a libretro
        // core is running; doing that leaves most hardware cores drawing into
        // a destroyed surface after the rotation.
        if (editingControls) saveControlPositions(layoutOrientation)
        layoutOrientation = newConfig.orientation
        applySessionLayout(layoutOrientation)
        surface.post { surface.onLayoutChanged() }
    }

    private fun applySessionLayout(orientation: Int) {
        val controls = findViewById<FrameLayout>(R.id.gameControls)
        val directionalPad = findViewById<View>(R.id.directionalPad)
        val leftStick = findViewById<View>(R.id.leftAnalogStick)
        val rightStick = findViewById<View>(R.id.rightAnalogStick)
        val actionButtons = findViewById<View>(R.id.actionButtons)
        val cButtonPad = findViewById<View>(R.id.cButtonPad)
        val centerButtons = findViewById<View>(R.id.centerButtons)
        controlGroups().forEach {
            // x/y dragging is represented internally as translation from the
            // gravity-based default. Reset it before applying a new screen
            // orientation, then restore that orientation's saved positions.
            it.translationX = 0f
            it.translationY = 0f
        }
        val hasLeftStick = leftStick.visibility == View.VISIBLE
        val hasRightStick = rightStick.visibility == View.VISIBLE
        val hasCPad = cButtonPad.visibility == View.VISIBLE
        val largeProfile = systemId in setOf("n64", "psp", "dreamcast", "gamecube", "ps2")
        val compactLandscapeCPad = orientation == Configuration.ORIENTATION_LANDSCAPE && hasCPad
        val dpadCell = when {
            compactLandscapeCPad -> 34
            largeProfile -> 44
            else -> 52
        }
        val cPadSize = if (compactLandscapeCPad) 84 else 102
        resizeDirectionalPad(dpadCell)
        if (hasCPad) resizeCPad(cPadSize / 3)
        controls.clipChildren = false
        controls.clipToPadding = false

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
            directionalPad.layoutParams = FrameLayout.LayoutParams(dp(dpadCell * 3), dp(dpadCell * 3)).apply {
                gravity = if (hasLeftStick) android.view.Gravity.START or android.view.Gravity.TOP else android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                leftMargin = dp(10)
                topMargin = dp(6)
            }
            if (hasLeftStick) {
                val stickSize = if (compactLandscapeCPad) 88 else 104
                leftStick.layoutParams = FrameLayout.LayoutParams(dp(stickSize), dp(stickSize)).apply {
                    gravity = android.view.Gravity.START or android.view.Gravity.BOTTOM
                    leftMargin = dp(24)
                    bottomMargin = dp(6)
                }
            }
            if (hasRightStick) {
                rightStick.layoutParams = FrameLayout.LayoutParams(dp(100), dp(100)).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.TOP
                    rightMargin = dp(192)
                    topMargin = dp(6)
                }
            }
            actionButtons.layoutParams = FrameLayout.LayoutParams(dp(180), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (hasRightStick) android.view.Gravity.END or android.view.Gravity.BOTTOM else android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                rightMargin = dp(4)
                bottomMargin = if (hasRightStick) dp(4) else 0
            }
            if (hasCPad) {
                cButtonPad.layoutParams = FrameLayout.LayoutParams(dp(cPadSize), dp(cPadSize)).apply {
                    gravity = android.view.Gravity.START or android.view.Gravity.BOTTOM
                    leftMargin = dp(if (compactLandscapeCPad) 46 else 30)
                    bottomMargin = dp(4)
                }
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
            val portraitControlHeight = when {
                hasCPad -> 310
                hasLeftStick || hasRightStick -> 300
                largeProfile -> 290
                else -> 245
            }
            surfaceHost.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                bottomMargin = dp(portraitControlHeight)
            }
            directionalPad.layoutParams = FrameLayout.LayoutParams(dp(dpadCell * 3), dp(dpadCell * 3)).apply {
                gravity = android.view.Gravity.START or android.view.Gravity.BOTTOM
                leftMargin = dp(12)
                bottomMargin = dp(if (hasLeftStick) 154 else 42)
            }
            if (hasLeftStick) {
                leftStick.layoutParams = FrameLayout.LayoutParams(dp(112), dp(112)).apply {
                    gravity = android.view.Gravity.START or android.view.Gravity.BOTTOM
                    leftMargin = dp(18)
                    bottomMargin = dp(8)
                }
            }
            if (hasRightStick) {
                rightStick.layoutParams = FrameLayout.LayoutParams(dp(108), dp(108)).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
                    rightMargin = dp(190)
                    bottomMargin = dp(8)
                }
            }
            actionButtons.layoutParams = FrameLayout.LayoutParams(dp(180), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
                rightMargin = dp(6)
                bottomMargin = dp(12)
            }
            if (hasCPad) {
                cButtonPad.layoutParams = FrameLayout.LayoutParams(dp(cPadSize), dp(cPadSize)).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                    bottomMargin = dp(6)
                }
            }
            centerButtons.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                bottomMargin = dp(if (hasCPad) 112 else 8)
            }
        }
        controls.post {
            ensureIndividualControlLayout()
            applySavedControlPositions(orientation)
        }
    }

    private fun enterControlEditMode() {
        editingControls = true
        wasPausedBeforeControlEdit = surface.isPaused()
        surface.setPaused(true)
        findViewById<Button>(R.id.editControlsButton).text = "Save"
        setToolbarActionsEnabled(false)
        findViewById<TextView>(R.id.sessionStatus).text =
            "Move controls · drag each button or stick, then tap Save"
        movableControls().filter { it.isShown }.forEach {
            it.scaleX = 1.04f
            it.scaleY = 1.04f
            it.elevation = dp(8).toFloat()
        }
    }

    private fun leaveControlEditMode(save: Boolean) {
        if (!editingControls) return
        if (save) saveControlPositions(layoutOrientation)
        draggedControl = null
        editingControls = false
        movableControls().forEach {
            it.scaleX = 1f
            it.scaleY = 1f
            it.elevation = 0f
        }
        surface.setPaused(wasPausedBeforeControlEdit)
        findViewById<Button>(R.id.editControlsButton).text = "Move"
        setToolbarActionsEnabled(true)
        findViewById<Button>(R.id.pauseButton).text = if (surface.isPaused()) "Resume" else "Pause"
        findViewById<TextView>(R.id.sessionStatus).text =
            if (save) "Controls saved for ${Systems.byId(systemId)?.shortName ?: systemId.uppercase()}" else lastSessionStatus
        if (save) findViewById<TextView>(R.id.sessionStatus).postDelayed({
            if (!editingControls) findViewById<TextView>(R.id.sessionStatus).text = lastSessionStatus
        }, 1500)
    }

    private fun setToolbarActionsEnabled(enabled: Boolean) {
        listOf(
            R.id.pauseButton,
            R.id.fastButton,
            R.id.quickSaveButton,
            R.id.quickLoadButton,
            R.id.resetButton,
        ).forEach { findViewById<View>(it).isEnabled = enabled }
    }

    private fun controlGroups(): List<View> = CONTROL_GROUPS.map { findViewById(it.second) }

    private fun movableControls(): List<View> = MOVABLE_CONTROLS.map { findViewById(it.second) }

    /**
     * Move every visible nested button into the top-level control overlay so
     * it can be positioned and touched independently. A same-sized Space is
     * left in the original GridLayout/LinearLayout as a layout anchor; this
     * preserves all existing system- and orientation-specific defaults.
     */
    private fun ensureIndividualControlLayout() {
        if (individualControlsReady) return
        val controls = findViewById<FrameLayout>(R.id.gameControls)
        if (controls.width <= 0 || controls.height <= 0) return
        val overlayLocation = IntArray(2)
        controls.getLocationOnScreen(overlayLocation)
        var laidOutControlFound = false

        MOVABLE_CONTROLS.forEach { (_, id) ->
            val view = findViewById<View>(id)
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@forEach
            laidOutControlFound = true
            if (view.parent === controls) return@forEach
            val parent = view.parent as? ViewGroup ?: return@forEach
            val index = parent.indexOfChild(view)
            val screenLocation = IntArray(2)
            view.getLocationOnScreen(screenLocation)
            val measuredWidth = view.width
            val measuredHeight = view.height
            val originalLayoutParams = view.layoutParams
            val placeholder = Space(this).apply {
                visibility = view.visibility
                layoutParams = originalLayoutParams
            }
            parent.removeViewAt(index)
            parent.addView(placeholder, index)
            controls.addView(view, FrameLayout.LayoutParams(measuredWidth, measuredHeight))
            view.translationX = 0f
            view.translationY = 0f
            view.x = (screenLocation[0] - overlayLocation[0]).toFloat()
            view.y = (screenLocation[1] - overlayLocation[1]).toFloat()
            controlPlaceholders[id] = placeholder
        }
        individualControlsReady = laidOutControlFound
    }

    private fun saveControlPositions(orientation: Int) {
        val controls = findViewById<FrameLayout>(R.id.gameControls)
        if (controls.width <= 0 || controls.height <= 0) return
        val positions = MOVABLE_CONTROLS.mapNotNull { (name, id) ->
            val view = findViewById<View>(id)
            if (!view.isShown || view.width <= 0 || view.height <= 0) return@mapNotNull null
            val maxX = (controls.width - view.width).coerceAtLeast(1)
            val maxY = (controls.height - view.height).coerceAtLeast(1)
            name to VirtualControlLayoutStore.Position(
                (view.x / maxX).coerceIn(0f, 1f),
                (view.y / maxY).coerceIn(0f, 1f),
            )
        }.toMap()
        controlLayoutStore.save(systemId, orientation, positions)
    }

    private fun applySavedControlPositions(orientation: Int) {
        val controls = findViewById<FrameLayout>(R.id.gameControls)
        if (!individualControlsReady || controls.width <= 0 || controls.height <= 0) return
        val overlayLocation = IntArray(2)
        controls.getLocationOnScreen(overlayLocation)
        MOVABLE_CONTROLS.forEach { (name, id) ->
            val view = findViewById<View>(id)
            if (!view.isShown) return@forEach
            controlPlaceholders[id]?.let { placeholder ->
                val defaultLocation = IntArray(2)
                placeholder.getLocationOnScreen(defaultLocation)
                view.x = (defaultLocation[0] - overlayLocation[0]).toFloat()
                view.y = (defaultLocation[1] - overlayLocation[1]).toFloat()
            }
            val saved = controlLayoutStore.position(systemId, orientation, name) ?: return@forEach
            val maxX = (controls.width - view.width).coerceAtLeast(0)
            val maxY = (controls.height - view.height).coerceAtLeast(0)
            view.x = saved.x * maxX
            view.y = saved.y * maxY
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!editingControls) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = movableControls().asReversed().firstOrNull { view ->
                    if (!view.isShown) return@firstOrNull false
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    event.rawX >= location[0] && event.rawX <= location[0] + view.width &&
                        event.rawY >= location[1] && event.rawY <= location[1] + view.height
                } ?: return super.dispatchTouchEvent(event)
                val targetLocation = IntArray(2)
                target.getLocationOnScreen(targetLocation)
                dragOffsetX = event.rawX - targetLocation[0]
                dragOffsetY = event.rawY - targetLocation[1]
                draggedControl = target
                target.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val target = draggedControl ?: return super.dispatchTouchEvent(event)
                val controls = findViewById<FrameLayout>(R.id.gameControls)
                val overlayLocation = IntArray(2)
                controls.getLocationOnScreen(overlayLocation)
                val maxX = (controls.width - target.width).coerceAtLeast(0).toFloat()
                val maxY = (controls.height - target.height).coerceAtLeast(0).toFloat()
                target.x = (event.rawX - overlayLocation[0] - dragOffsetX).coerceIn(0f, maxX)
                target.y = (event.rawY - overlayLocation[1] - dragOffsetY).coerceIn(0f, maxY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggedControl != null) {
                    draggedControl = null
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** Keep the d-pad compact on systems that also show a stick and extra buttons. */
    private fun resizeDirectionalPad(cell: Int) {
        val pad = findViewById<GridLayout>(R.id.directionalPad)
        pad.layoutParams = (pad.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(cell * 3, cell * 3)).apply {
            width = dp(cell * 3)
            height = dp(cell * 3)
        }
        for (index in 0 until pad.childCount) {
            pad.getChildAt(index).layoutParams = GridLayout.LayoutParams().apply {
                width = dp(cell)
                height = dp(cell)
            }
        }
    }

    private fun resizeCPad(cell: Int) {
        val pad = findViewById<GridLayout>(R.id.cButtonPad)
        for (index in 0 until pad.childCount) {
            pad.getChildAt(index).layoutParams = GridLayout.LayoutParams().apply {
                width = dp(cell)
                height = dp(cell)
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

    /**
     * Each core gets the controller shape it actually exposes through the
     * libretro RetroPad/analogue interfaces. Unused controls are removed from
     * the touch target entirely so they cannot cover the game surface.
     */
    private fun configureControlProfile() {
        val isGba = systemId == "gba"
        val isGbc = systemId == "gbc"
        val isNes = systemId == "nes"
        val isSnes = systemId == "snes"
        val isGenesis = systemId == "genesis"
        val isPs1 = systemId == "ps1"
        val isPsp = systemId == "psp"
        val isN64 = systemId == "n64"
        val isDreamcast = systemId == "dreamcast"
        val isGameCube = systemId == "gamecube"
        val isPs2 = systemId == "ps2"

        val showLeftStick = isPsp || isN64 || isDreamcast || isGameCube || isPs2
        val showRightStick = isGameCube || isPs2
        val showL1R1 = isGba || isSnes || isPs1 || isPsp || isGameCube || isPs2
        val showFaceExtra = isSnes || isGenesis || isPs1 || isPsp || isDreamcast || isGameCube || isPs2
        val showZ = isN64 || isGameCube
        val showL2R2 = isDreamcast || isPs1 || isPs2
        val showCPad = isN64
        val showSelect = !isGenesis && !isN64 && !isDreamcast && !isGameCube && !isPs2

        val leftStick = findViewById<AnalogStickView>(R.id.leftAnalogStick)
        val rightStick = findViewById<AnalogStickView>(R.id.rightAnalogStick)
        leftStick.visibility = if (showLeftStick) View.VISIBLE else View.GONE
        rightStick.visibility = if (showRightStick) View.VISIBLE else View.GONE
        leftStick.contentDescription = if (isN64) "N64 analog stick" else "Left analog stick"
        rightStick.contentDescription = "Right analog stick"
        leftStick.onValueChanged = { x, y -> surface.setAnalog(0, x, y) }
        rightStick.onValueChanged = { x, y -> surface.setAnalog(1, x, y) }

        findViewById<View>(R.id.lButton).visibility = if (showL1R1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rButton).visibility = if (showL1R1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.shoulderRow).visibility = if (showL1R1) View.VISIBLE else View.GONE
        val showPlayStationFace = isPs1 || isPsp || isPs2
        findViewById<View>(R.id.faceRow).visibility = if (showPlayStationFace) View.GONE else View.VISIBLE
        findViewById<View>(R.id.faceExtraRow).visibility = if (showFaceExtra && !showPlayStationFace) View.VISIBLE else View.GONE
        findViewById<View>(R.id.playStationFaceButtons).visibility = if (showPlayStationFace) View.VISIBLE else View.GONE
        findViewById<View>(R.id.extraButtonGrid).visibility = if (showZ || showL2R2) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cButtonPad).visibility = if (showCPad) View.VISIBLE else View.GONE
        findViewById<View>(R.id.selectButton).visibility = if (showSelect) View.VISIBLE else View.GONE

        val a = findViewById<Button>(R.id.aButton)
        val b = findViewById<Button>(R.id.bButton)
        val x = findViewById<Button>(R.id.xButton)
        val y = findViewById<Button>(R.id.yButton)
        val l = findViewById<Button>(R.id.lButton)
        val r = findViewById<Button>(R.id.rButton)
        val z = findViewById<Button>(R.id.zButton)
        val l2 = findViewById<Button>(R.id.l2Button)
        val r2 = findViewById<Button>(R.id.r2Button)
        a.text = "A"; b.text = "B"; x.text = "X"; y.text = if (isGenesis) "C" else "Y"
        l.text = if (isPs1 || isPs2) "L1" else "L"
        r.text = if (isPs1 || isPs2) "R1" else "R"
        z.text = "Z"
        l2.text = if (isDreamcast) "LT" else "L2"
        r2.text = if (isDreamcast) "RT" else "R2"
        x.visibility = if (showFaceExtra && !isGenesis) View.VISIBLE else View.GONE
        y.visibility = if (showFaceExtra) View.VISIBLE else View.GONE
        z.visibility = if (showZ) View.VISIBLE else View.GONE
        l2.visibility = if (showL2R2) View.VISIBLE else View.GONE
        r2.visibility = if (showL2R2) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.startButton).text = if (isGenesis) "Start" else "Start"
        findViewById<Button>(R.id.selectButton).text = "Select"

        // Physical N64 C-buttons are represented by the extended RetroPad
        // buttons. This keeps them independent from the regular A/B face pair.
        findViewById<Button>(R.id.cUpButton).contentDescription = "C up"
        findViewById<Button>(R.id.cDownButton).contentDescription = "C down"
        findViewById<Button>(R.id.cLeftButton).contentDescription = "C left"
        findViewById<Button>(R.id.cRightButton).contentDescription = "C right"
        bindControls()
    }

    private fun bindControls() {
        mapOf(
            R.id.upButton to ControllerMappingStore.BUTTON_UP,
            R.id.downButton to ControllerMappingStore.BUTTON_DOWN,
            R.id.leftButton to ControllerMappingStore.BUTTON_LEFT,
            R.id.rightButton to ControllerMappingStore.BUTTON_RIGHT,
            R.id.aButton to (if (systemId == "dreamcast") ControllerMappingStore.BUTTON_B else ControllerMappingStore.BUTTON_A),
            R.id.bButton to (if (systemId == "dreamcast") ControllerMappingStore.BUTTON_A else ControllerMappingStore.BUTTON_B),
            R.id.xButton to ControllerMappingStore.BUTTON_X,
            R.id.yButton to ControllerMappingStore.BUTTON_Y,
            // Libretro's PlayStation convention is B=Cross, A=Circle,
            // Y=Square and X=Triangle. Keep the visible symbols and native
            // inputs aligned for PCSX-ReARMed, Play! and PPSSPP.
            R.id.crossButton to ControllerMappingStore.BUTTON_B,
            R.id.circleButton to ControllerMappingStore.BUTTON_A,
            R.id.squareButton to ControllerMappingStore.BUTTON_Y,
            R.id.triangleButton to ControllerMappingStore.BUTTON_X,
            R.id.lButton to ControllerMappingStore.BUTTON_L,
            R.id.rButton to ControllerMappingStore.BUTTON_R,
            R.id.zButton to ControllerMappingStore.BUTTON_L2,
            R.id.l2Button to ControllerMappingStore.BUTTON_L2,
            R.id.r2Button to ControllerMappingStore.BUTTON_R2,
            R.id.cUpButton to ControllerMappingStore.BUTTON_Y,
            R.id.cLeftButton to ControllerMappingStore.BUTTON_X,
            R.id.cRightButton to ControllerMappingStore.BUTTON_R2,
            R.id.cDownButton to ControllerMappingStore.BUTTON_L3,
            R.id.selectButton to ControllerMappingStore.BUTTON_SELECT,
            R.id.startButton to ControllerMappingStore.BUTTON_START,
        )
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
        if (editingControls) leaveControlEditMode(save = true)
        explicitExit = true
        super.onBackPressed()
    }

    override fun onDestroy() {
        // Do not infer an emulation exit from Activity destruction. During a
        // rotation Android may tear down the old window and report it as
        // finishing even though the user is still in the same session.
        if (editingControls) saveControlPositions(layoutOrientation)
        if (explicitExit) surface.stop()
        super.onDestroy()
    }
    companion object {
        private val CONTROL_GROUPS = listOf(
            "dpad" to R.id.directionalPad,
            "left_stick" to R.id.leftAnalogStick,
            "right_stick" to R.id.rightAnalogStick,
            "actions" to R.id.actionButtons,
            "c_buttons" to R.id.cButtonPad,
            "center" to R.id.centerButtons,
        )
        private val MOVABLE_CONTROLS = listOf(
            "up" to R.id.upButton,
            "down" to R.id.downButton,
            "left" to R.id.leftButton,
            "right" to R.id.rightButton,
            "left_stick" to R.id.leftAnalogStick,
            "right_stick" to R.id.rightAnalogStick,
            "a" to R.id.aButton,
            "b" to R.id.bButton,
            "x" to R.id.xButton,
            "y" to R.id.yButton,
            "triangle" to R.id.triangleButton,
            "square" to R.id.squareButton,
            "circle" to R.id.circleButton,
            "cross" to R.id.crossButton,
            "l" to R.id.lButton,
            "r" to R.id.rButton,
            "z" to R.id.zButton,
            "l2" to R.id.l2Button,
            "r2" to R.id.r2Button,
            "c_up" to R.id.cUpButton,
            "c_down" to R.id.cDownButton,
            "c_left" to R.id.cLeftButton,
            "c_right" to R.id.cRightButton,
            "select" to R.id.selectButton,
            "start" to R.id.startButton,
        )
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
