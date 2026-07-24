package com.keyquest.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.keyquest.app.display.DisplayModes
import com.keyquest.app.midi.MidiEngine
import com.keyquest.app.ui.KeyQuestApp

class MainActivity : ComponentActivity() {

    private lateinit var midiEngine: MidiEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SPEC §1 fullscreen game behavior: immersive sticky, keep-screen-on.
        // Landscape lock is in the manifest.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        midiEngine = MidiEngine(this)
        midiEngine.start()

        setContent {
            KeyQuestApp(
                engine = midiEngine,
                currentRefreshRate = { DisplayModes.currentRefreshRate(this) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // SPEC §1: hold the 144Hz mode explicitly, every time we come back.
        DisplayModes.requestHighestRefreshRate(this)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        midiEngine.stop()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
