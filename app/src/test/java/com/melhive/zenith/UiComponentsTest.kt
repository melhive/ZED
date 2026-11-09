package com.melhive.zenith

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.melhive.zenith.agents.ConfirmationRequest
import com.melhive.zenith.ui.components.ConfirmationDialog
import com.melhive.zenith.ui.components.RealProgress
import com.melhive.zenith.ui.components.StatusPill
import com.melhive.zenith.ui.components.ZenithOrb
import com.melhive.zenith.ui.theme.ZenithTheme
import com.melhive.zenith.voice.OrbState
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun orbRendersEveryState() {
        val state = mutableStateOf(OrbState.IDLE)
        compose.setContent { ZenithTheme { ZenithOrb(state.value, micLevel = 0.6f, speechPulse = 3) } }
        for (s in OrbState.entries) {
            state.value = s
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
        }
    }

    @Test fun confirmationDialogReportsDecision() {
        var answer: Boolean? = null
        compose.setContent {
            ZenithTheme {
                ConfirmationDialog(ConfirmationRequest(1, "Delete file", "Permanently delete “a.txt”?", destructive = true, confirmLabel = "Delete")) { answer = it }
            }
        }
        compose.onNodeWithText("Permanently delete “a.txt”?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(true, answer)
    }

    @Test fun progressAndPillsRender() {
        compose.setContent {
            ZenithTheme {
                Column {
                    StatusPill("Running")
                    RealProgress(3L, 10L)
                    RealProgress(null, null)
                }
            }
        }
        compose.onNodeWithText("Running").assertIsDisplayed()
    }
}
