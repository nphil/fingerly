package com.fingerly.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fingerly.app.display.DisplayModes
import com.fingerly.app.log.RemoteLog
import com.fingerly.app.midi.MidiEngine
import com.fingerly.app.ui.FingerlyApp
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var midiEngine: MidiEngine

    /** Bumped on every launcher relaunch: the app must reopen into today's session (SPEC §1). */
    private val relaunchSignal = MutableStateFlow(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        relaunchSignal.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SPEC §1 fullscreen game behavior: immersive sticky, keep-screen-on.
        // Landscape lock is in the manifest.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        RemoteLog.init(this)
        RemoteLog.log(
            "app",
            "start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "${Build.MODEL} Android ${Build.VERSION.RELEASE}",
        )

        midiEngine = MidiEngine(this)
        midiEngine.start()

        setContent {
            FingerlyApp(
                engine = midiEngine,
                currentRefreshRate = { DisplayModes.currentRefreshRate(this) },
                relaunchSignal = relaunchSignal,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // SPEC §1: hold the highest available mode explicitly, every time we come back.
        val requested = DisplayModes.requestHighestRefreshRate(this)
        RemoteLog.log(
            "display",
            "requested ${requested.toInt()}Hz, now ${DisplayModes.currentRefreshRate(this).toInt()}Hz",
        )
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
