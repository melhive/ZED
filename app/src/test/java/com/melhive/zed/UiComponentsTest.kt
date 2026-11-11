package com.melhive.zed

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.melhive.zed.agents.ConfirmationRequest
import com.melhive.zed.ui.components.ConfirmationDialog
import com.melhive.zed.ui.components.RealProgress
import com.melhive.zed.ui.components.StatusPill
import com.melhive.zed.ui.components.ZedOrb
import com.melhive.zed.ui.theme.ZedTheme
import com.melhive.zed.voice.OrbState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
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

    @Test fun brainGlyphAnimates() {
        compose.setContent { ZedTheme { com.melhive.zed.ui.screens.BrainGlyph(androidx.compose.ui.Modifier.size(64.dp)) } }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
    }

    @Test fun orbRendersEveryState() {
        val state = mutableStateOf(OrbState.IDLE)
        compose.setContent { ZedTheme { ZedOrb(state.value, micLevel = 0.6f, speechPulse = 3) } }
        for (s in OrbState.entries) {
            state.value = s
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
        }
    }

    @Test fun confirmationDialogReportsDecision() {
        var answer: Boolean? = null
        compose.setContent {
            ZedTheme {
                ConfirmationDialog(ConfirmationRequest(1, "Delete file", "Permanently delete “a.txt”?", destructive = true, confirmLabel = "Delete")) { answer = it }
            }
        }
        compose.onNodeWithText("Permanently delete “a.txt”?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(true, answer)
    }

    @Test fun progressAndPillsRender() {
        compose.setContent {
            ZedTheme {
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
