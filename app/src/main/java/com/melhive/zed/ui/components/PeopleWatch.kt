package com.melhive.zed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.melhive.zed.core.eyes.EyeMood
import com.melhive.zed.eyes.EyeCamera
import com.melhive.zed.eyes.EyePeople
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.voice.OrbState
import kotlinx.coroutines.delay

/**
 * Recognising people while a voice screen (the orb or the eyes) is open: the camera's face pictures go to
 * [people], known faces are greeted once, and someone new who stays is asked their name once. [onLine]
 * receives the test line ("Sees: … Hears: …").
 */
@Composable
fun PeopleWatch(enabled: Boolean, camera: EyeCamera, people: EyePeople, onMood: (EyeMood) -> Unit = {}, onLine: (String) -> Unit) {
    val c = rememberContainer()
    val live = c.live
    val mood by rememberUpdatedState(onMood)
    val line by rememberUpdatedState(onLine)
    DisposableEffect(enabled) {
        if (enabled) {
            camera.onFaceCrop = { people.onFace(it) }
            live.onUtterance = { people.onUtterance(it) }
            live.intercept = { said -> people.nameAnswer(said) }
        }
        onDispose { camera.onFaceCrop = null; live.onUtterance = null; live.intercept = null }
    }
    LaunchedEffect(enabled) {
        if (!enabled) { line(""); return@LaunchedEffect }
        var goneFor = 0
        while (true) {
            delay(500)
            if (camera.faceSeen) goneFor = 0 else if (++goneFor == 4) people.onNoFace()
            line(
                if (!c.people.ownerHasFace() && people.teaching < 0) "Teach ZED your face in More → Who ZED knows"
                else "Sees: " + (if (!camera.running) "camera off" else if (people.teaching >= 0) "learning your face ${people.teaching}/${EyePeople.TEACH}" else people.seenLabel) +
                    (people.heardLabel.takeIf { it.isNotEmpty() }?.let { "   Hears: $it" } ?: ""),
            )
            val now = live.state.value
            // Only speak up when nobody is in the middle of saying something.
            val free = now.active && now.partial.isEmpty() && (now.orb == OrbState.LISTENING || now.orb == OrbState.IDLE) && !now.micMuted
            if (!free || people.teaching >= 0) continue
            val who = people.seen
            if (who != null && people.greeted.add(who.id)) {
                if (who.owner) mood(EyeMood.HAPPY)
                live.announce(if (who.owner) "Hello, ${who.name}." else "Hello again, ${who.name}.")
            } else if (people.strangerStable && !people.asked && c.people.ownerHasFace()) {
                // Someone it doesn't know has stayed: ask once, and remember them if they answer.
                mood(EyeMood.CURIOUS)
                people.askedName()
                live.announce("I don't think we've met. What's your name?")
            }
        }
    }
}

/** Shown while a voice that isn't the owner's is being answered. Tapping it lets the owner prove who they are. */
@Composable
fun GuestPill(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val c = rememberContainer()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.Text(
        "GUEST · TAP IF IT'S YOU", color = com.melhive.zed.ui.theme.ZedColors.Amber,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(androidx.compose.ui.graphics.Color(0xCC0B111E), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .clickable {
                val activity = context as? android.app.Activity ?: return@clickable
                com.melhive.zed.security.DeviceAuth.confirm(activity, "It's me", "Unlock to get full access back") { ok -> if (ok) c.live.trustVoice() }
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
