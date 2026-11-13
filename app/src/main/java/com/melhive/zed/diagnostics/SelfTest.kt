package com.melhive.zed.diagnostics

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import com.melhive.zed.AppContainer
import com.melhive.zed.android.PermissionCatalog
import com.melhive.zed.core.intelligence.ChatTurn
import com.melhive.zed.core.intelligence.Role
import com.melhive.zed.core.voice.VoiceCatalog
import com.melhive.zed.intelligence.GenerationEvent
import com.melhive.zed.intelligence.GenerationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Checks, on this phone, that each thing ZED does actually works — safely: it writes
 * and immediately removes a test task, reminder, calendar event and file, and never
 * sends, calls or changes anything you would notice.
 */
class SelfTest(private val context: Context, private val c: AppContainer) {
    enum class Status { PASS, WARN, FAIL }
    data class Check(val name: String, val status: Status, val detail: String, val ms: Long)

    suspend fun run(onCheck: (Check) -> Unit) {
        suspend fun check(name: String, block: suspend () -> Pair<Status, String>) {
            val t0 = System.currentTimeMillis()
            val (status, detail) = try { block() } catch (e: Throwable) { Status.FAIL to (e.message ?: e.javaClass.simpleName) }
            onCheck(Check(name, status, detail, System.currentTimeMillis() - t0))
        }
        fun has(p: com.melhive.zed.android.PermissionInfo) = PermissionCatalog.isGranted(context, p)
        val pass = Status.PASS; val warn = Status.WARN; val fail = Status.FAIL

        check("Database") {
            val t = c.todos.add("ZED self-test", null)
            c.todos.delete(t.id)
            pass to "Saved and removed a test task"
        }
        check("Reminders") {
            val r = c.reminderScheduler.create("ZED self-test", System.currentTimeMillis() + 3_650L * 86_400_000L)
            c.reminderScheduler.deleteReminder(r.id)
            if (r.exact) pass to "Scheduled and cancelled a test reminder (exact timing)"
            else warn to "Works, but timing is approximate. Allow “Alarms & reminders” for exact reminders"
        }
        check("Notifications") {
            if (has(PermissionCatalog.NOTIFICATIONS)) pass to "Allowed" else fail to "Not allowed: reminders and replies can't notify you"
        }
        check("Calendar") {
            val writer = com.melhive.zed.agents.CalendarWriter(context)
            if (!writer.granted()) return@check warn to "No calendar access: ZED can't add, move or delete events"
            val start = java.time.LocalDateTime.now().plusYears(5)
            val (id, cal) = withContext(Dispatchers.IO) { writer.insert("ZED self-test", start, start.plusMinutes(15), null, null) } ?: return@check fail to "The calendar refused a test event"
            withContext(Dispatchers.IO) { context.contentResolver.delete(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) }
            pass to "Added and removed a test event in “${cal.name}”"
        }
        check("Clock app") {
            val ok = Intent(AlarmClock.ACTION_SET_ALARM).resolveActivity(context.packageManager) != null
            if (ok) pass to "Alarms and timers can be set" else fail to "No clock app accepts alarms"
        }
        check("Contacts") {
            if (!has(PermissionCatalog.CONTACTS)) return@check warn to "No access: say numbers instead of names"
            val n = withContext(Dispatchers.IO) { context.contentResolver.query(android.provider.ContactsContract.Contacts.CONTENT_URI, arrayOf(android.provider.ContactsContract.Contacts._ID), null, null, null)?.use { it.count } ?: 0 }
            pass to "$n contacts readable"
        }
        check("Calls") { if (has(PermissionCatalog.CALL_PHONE)) pass to "Calls start directly" else warn to "Calls open the dialer first (allow Phone to call directly)" }
        check("Text messages") { if (has(PermissionCatalog.SMS)) pass to "Texts send directly" else warn to "Texts open your messaging app first (allow SMS to send directly)" }
        check("Message replies") {
            if (com.melhive.zed.android.ZedNotificationListener.hasAccess(context)) pass to "Can read and reply to messages from notifications"
            else warn to "Allow notification access to read and reply to WhatsApp, Messenger and other chats"
        }
        check("Floating orb") {
            if (Settings.canDrawOverlays(context)) pass to "Can open over other apps" else warn to "Allow “Display over other apps” so “Hey Zed” opens over any app"
        }
        check("Microphone") { if (has(PermissionCatalog.MICROPHONE)) pass to "Allowed" else fail to "Not allowed: ZED can't hear you" }
        check("Speech recognition") {
            val onDevice = c.voicePacks.isInstalled(VoiceCatalog.SPEECH)
            val android = PermissionCatalog.speechRecognitionAvailable(context)
            when {
                onDevice -> pass to "On-device recognition installed (offline, no beep)"
                android -> warn to "Using the phone's speech service. Download on-device recognition in More → Voice for offline use"
                else -> fail to "No speech recognition available"
            }
        }
        check("Wake word") {
            if (!c.voicePacks.isInstalled(VoiceCatalog.WAKE_WORD)) return@check warn to "“Hey Zed” model not downloaded"
            if (!c.settings.current().wakeWordEnabled) return@check warn to "Installed but turned off"
            val pm = context.getSystemService(android.os.PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) warn to "On, but battery optimisation may stop it in the background"
            else pass to "Listening, exempt from battery optimisation"
        }
        check("Voice") {
            val ready = withTimeoutOrNull(20_000) { c.voice.awaitReady() } ?: false
            if (!ready) return@check fail to "The voice couldn't load"
            val status = c.voice.status.value
            if (status.phase == com.melhive.zed.voice.VoiceManager.Phase.FALLBACK) warn to (status.detail ?: "Using the phone voice") else pass to "${status.voiceLabel} ready"
        }
        check("AI model") {
            val provider = c.providers.active() ?: return@check fail to "No model downloaded (Model Manager)"
            val out = StringBuilder()
            val t0 = System.currentTimeMillis()
            var first = -1L
            withTimeoutOrNull(90_000) {
                provider.generate(GenerationRequest(system = "Reply with one word.", turns = listOf(ChatTurn(Role.USER, "Say OK.")), maxOutputTokens = 8, sessionKey = "self-test"))
                    .collect { e -> if (e is GenerationEvent.Delta) { if (first < 0) first = System.currentTimeMillis() - t0; out.append(e.text) } }
            } ?: return@check fail to "No answer within 90 s"
            if (out.isBlank()) fail to "The model gave no answer" else pass to "${provider.label} answered (first word after ${first} ms)"
        }
        check("Files") {
            val grants = c.files.validGrants()
            if (grants.isEmpty()) return@check warn to "No folder granted: file commands need one (Files)"
            val path = "${grants.first().displayName}/.zed-selftest.txt"
            c.files.createFile(path, "ok")
            val read = c.files.readText(path)
            c.files.delete(path)
            if (read.trim() == "ok") pass to "Wrote, read and removed a test file in ${grants.first().displayName}" else fail to "The test file read back wrong"
        }
        check("Screen control") {
            if (com.melhive.zed.assist.ZedAccessibilityService.isEnabled(context)) pass to "Can read and act on the screen"
            else warn to "Turn on ZED in Accessibility settings to let it read your screen and tap, scroll or type for you"
        }
        check("Internet") {
            val online = com.melhive.zed.android.DeviceInfo.snapshot(context).online
            if (online) pass to "Online (only weather, web research and downloads use it)" else warn to "Offline: everything else still works"
        }
    }
}
