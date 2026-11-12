package com.melhive.zed

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import com.melhive.zed.ui.navigation.ZedRoot
import com.melhive.zed.ui.theme.ZedTheme

class MainActivity : ComponentActivity() {
    private val requestedRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedRoute.value = route(intent)
        setContent {
            ZedTheme {
                ZedRoot(requestedRoute = requestedRoute.value, onRouteConsumed = { requestedRoute.value = null })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The wake word service may only start while ZED is on screen (Android 14 microphone rules).
        val c = (application as ZedApp).container
        // Have Gemma ready before the first question.
        c.warmUpModel()
        c.appScope.launch {
            val s = c.settings.current()
            if (s.wakeWordEnabled && c.voicePacks.isInstalled(com.melhive.zed.core.voice.VoiceCatalog.WAKE_WORD) &&
                com.melhive.zed.android.PermissionCatalog.isGranted(this@MainActivity, com.melhive.zed.android.PermissionCatalog.MICROPHONE)) {
                com.melhive.zed.voice.WakeWordService.start(this@MainActivity)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedRoute.value = route(intent)
    }

    /** "live:<prompt>" opens LIVE and sends the prompt (e.g. the briefing notification). */
    private fun route(intent: Intent?): String? {
        val r = intent?.getStringExtra(EXTRA_ROUTE) ?: return null
        if (r.startsWith("live:")) {
            (application as ZedApp).container.livePrompt.value = r.removePrefix("live:")
            return "live"
        }
        return r
    }

    companion object { const val EXTRA_ROUTE = "route" }
}
