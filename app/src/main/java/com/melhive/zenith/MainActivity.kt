package com.melhive.zenith

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.melhive.zenith.ui.navigation.ZenithRoot
import com.melhive.zenith.ui.theme.ZenithTheme

class MainActivity : ComponentActivity() {
    private val requestedRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedRoute.value = route(intent)
        setContent {
            ZenithTheme {
                ZenithRoot(requestedRoute = requestedRoute.value, onRouteConsumed = { requestedRoute.value = null })
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
            (application as ZenithApp).container.livePrompt.value = r.removePrefix("live:")
            return "live"
        }
        return r
    }

    companion object { const val EXTRA_ROUTE = "route" }
}
