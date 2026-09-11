package com.beefbeefs.emuall

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class EmulationActivity : AppCompatActivity() {
    private lateinit var surface: GameSurfaceView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_emulation)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        surface = findViewById(R.id.gameSurface)
        val rom = intent.getStringExtra(EXTRA_ROM) ?: return finish()
        val save = intent.getStringExtra(EXTRA_SAVE) ?: return finish()
        bindControls()
        surface.start("libmgba_libretro.so", rom, save, filesDir.absolutePath) { status ->
            findViewById<TextView>(R.id.sessionStatus).text = status
        }
        findViewById<Button>(R.id.menuButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.pauseButton).setOnClickListener { button ->
            surface.setPaused(!surface.isPaused()); (button as Button).text = if (surface.isPaused()) "Resume" else "Pause"
        }
        findViewById<Button>(R.id.resetButton).setOnClickListener { surface.resetGame() }
        findViewById<Button>(R.id.fastButton).setOnClickListener { button -> (button as Button).text = if (surface.toggleFastForward()) "FF 3×" else "FF" }
    }
    private fun bindControls() {
        mapOf(R.id.upButton to 4, R.id.downButton to 5, R.id.leftButton to 6, R.id.rightButton to 7,
            R.id.aButton to 8, R.id.bButton to 0, R.id.lButton to 10, R.id.rButton to 11, R.id.selectButton to 2, R.id.startButton to 3)
            .forEach { (viewId, buttonId) -> findViewById<Button>(viewId).setOnTouchListener { _, event ->
                when (event.actionMasked) { MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> surface.setButton(buttonId, true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> surface.setButton(buttonId, false) }; true } }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val id = when (event.keyCode) { KeyEvent.KEYCODE_DPAD_UP -> 4; KeyEvent.KEYCODE_DPAD_DOWN -> 5; KeyEvent.KEYCODE_DPAD_LEFT -> 6; KeyEvent.KEYCODE_DPAD_RIGHT -> 7
            KeyEvent.KEYCODE_BUTTON_A -> 8; KeyEvent.KEYCODE_BUTTON_B -> 0; KeyEvent.KEYCODE_BUTTON_L1 -> 10; KeyEvent.KEYCODE_BUTTON_R1 -> 11
            KeyEvent.KEYCODE_BUTTON_SELECT -> 2; KeyEvent.KEYCODE_BUTTON_START -> 3; else -> null }
        if (id != null) { surface.setButton(id, event.action == KeyEvent.ACTION_DOWN); return true }
        return super.dispatchKeyEvent(event)
    }
    override fun onDestroy() { surface.stop(); super.onDestroy() }
    companion object { const val EXTRA_ROM = "rom"; const val EXTRA_SAVE = "save" }
}
