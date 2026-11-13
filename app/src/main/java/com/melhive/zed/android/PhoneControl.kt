package com.melhive.zed.android

import android.Manifest
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

/** Phone and device control used by the phone/device tools. Every method returns a user-facing result. */
class PhoneControl(private val context: Context) {
    data class Contact(val name: String, val number: String, val type: String)

    private val audio get() = context.getSystemService(AudioManager::class.java)

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    // ---- contacts, calls, messages ----

    fun hasContactsPermission() = granted(Manifest.permission.READ_CONTACTS)
    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun findContacts(name: String, limit: Int = 8): List<Contact> {
        if (!hasContactsPermission()) return emptyList()
        val out = LinkedHashMap<String, Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${name.trim()}%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 40) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(2), "").toString()
                out.putIfAbsent("$n|${num.filter { it.isDigit() || it == '+' }}", Contact(n, num, type))
            }
        }
        val q = name.trim().lowercase()
        return out.values.sortedWith(compareBy<Contact>({ it.name.lowercase() != q }, { !it.name.lowercase().startsWith(q) }, { it.type != "Mobile" }, { it.name })).take(limit)
    }

    sealed interface Target {
        data class Number(val number: String, val label: String) : Target
        data class Problem(val message: String) : Target
    }

    /** Resolves a contact name or a phone number to a single number. */
    fun resolve(who: String): Target {
        val w = who.trim()
        val digits = w.filter { it.isDigit() }
        if (digits.length >= 3 && w.all { it.isDigit() || it in "+-() ." }) return Target.Number(w.filter { it.isDigit() || it == '+' }, w)
        if (!hasContactsPermission()) return Target.Problem("I need contacts permission to find “$w”. Allow it in More → Permissions & Diagnostics, or say the number.")
        val matches = findContacts(w)
        if (matches.isEmpty()) return Target.Problem("I couldn't find “$w” in your contacts.")
        val people = matches.map { it.name }.distinct()
        val exact = matches.filter { it.name.equals(w, ignoreCase = true) }
        val pick = when {
            exact.isNotEmpty() -> exact.first()
            people.size == 1 -> matches.first()
            else -> return Target.Problem("Several contacts match “$w”: ${people.take(5).joinToString()}. Which one?")
        }
        return Target.Number(pick.number, "${pick.name} (${pick.type.ifBlank { "phone" }} ${pick.number})")
    }

    /** Places the call directly when CALL_PHONE is granted, otherwise opens the dialer with the number. */
    fun call(number: String): String {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return if (granted(Manifest.permission.CALL_PHONE) && start(Intent(Intent.ACTION_CALL, uri))) "Calling…"
        else if (start(Intent(Intent.ACTION_DIAL, uri))) "Opened the dialer — tap call to start."
        else "No phone app is available."
    }

    /** Sends right away when SMS permission is granted; returns false to fall back to the messaging app. */
    fun sendSms(number: String, message: String): Boolean {
        if (!granted(Manifest.permission.SEND_SMS)) return false
        return runCatching {
            val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(android.telephony.SmsManager::class.java) else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            val parts = sms.divideMessage(message)
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null) else sms.sendTextMessage(number, null, message, null, null)
        }.isSuccess
    }

    /** Opens a WhatsApp chat with [message] typed in (WhatsApp needs the number in international form). */
    fun openWhatsApp(number: String, message: String): Boolean {
        var digits = number.filter { it.isDigit() || it == '+' }
        if (digits.startsWith("+")) digits = digits.drop(1)
        else if (digits.startsWith("00")) digits = digits.drop(2)
        else if (digits.startsWith("0")) {
            val iso = runCatching { context.getSystemService(android.telephony.TelephonyManager::class.java).simCountryIso.lowercase() }.getOrDefault("")
            val code = mapOf("ph" to "63", "us" to "1", "ca" to "1", "gb" to "44", "au" to "61", "in" to "91", "id" to "62", "my" to "60", "sg" to "65", "ae" to "971", "sa" to "966", "jp" to "81", "kr" to "82", "de" to "49", "fr" to "33", "es" to "34", "it" to "39", "nz" to "64", "hk" to "852", "th" to "66", "vn" to "84")[iso]
            if (code != null) digits = code + digits.drop(1)
        }
        return start(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=" + Uri.encode(message))).setPackage("com.whatsapp")) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=" + Uri.encode(message))).setPackage("com.whatsapp.w4b"))
    }

    fun composeSms(number: String, message: String): Boolean =
        start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", message))

    // ---- apps, media, web, maps, camera ----

    fun openApp(name: String): String {
        val pm = context.packageManager
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = name.trim().lowercase().removePrefix("the ").removeSuffix(" app")
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val scored = launchers.mapNotNull { ri ->
            val label = ri.loadLabel(pm).toString()
            val l = norm(label); val n = norm(q)
            val score = when {
                l == n -> 0
                l.startsWith(n) -> 1
                l.contains(n) -> 2
                n.contains(l) && l.length >= 3 -> 3
                ri.activityInfo.packageName.lowercase().contains(n) -> 4
                else -> return@mapNotNull null
            }
            Triple(score, label, ri.activityInfo.packageName)
        }.sortedWith(compareBy({ it.first }, { it.second.length }))
        val best = scored.firstOrNull() ?: return "I couldn't find an app called “$name”."
        val intent = pm.getLaunchIntentForPackage(best.third) ?: return "“${best.second}” can't be opened."
        return if (start(intent)) "Opened ${best.second}." else "“${best.second}” can't be opened."
    }

    fun media(action: String): String {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return when (action) {
            "play" -> "Playing."
            "pause" -> "Paused."
            "next" -> "Skipped to the next track."
            "previous" -> "Back to the previous track."
            "stop" -> "Stopped."
            else -> "Toggled playback."
        }
    }

    fun playMusic(query: String): String {
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (start(search)) return "Asked your music app to play “$query”."
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(query))))) "Opened a search for “$query”." else "No music app is available."
    }

    fun openWeb(query: String): String {
        val q = query.trim()
        val looksLikeUrl = Regex("^(https?://)?[\\w-]+(\\.[\\w-]+)+(/\\S*)?$").matches(q)
        if (looksLikeUrl) return if (start(Intent(Intent.ACTION_VIEW, Uri.parse(if (q.startsWith("http")) q else "https://$q")))) "Opened $q." else "No browser is available."
        if (start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))) return "Searching the web for “$q”."
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))) "Searching the web for “$q”." else "No browser is available."
    }

    fun navigate(destination: String, mode: String?): String {
        val m = when (mode) { "walking" -> "w"; "transit" -> "r"; "bicycling" -> "b"; else -> "d" }
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination) + "&mode=" + m))
        if (start(nav)) return "Starting directions to $destination."
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination))))) "Showing $destination on the map." else "No maps app is available."
    }

    fun camera(mode: String?): String {
        val action = if (mode == "video") MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        return if (start(Intent(action))) "Camera open." else "No camera app is available."
    }

    fun settingsPanel(panel: String): String {
        val candidates = buildList {
            when (panel) {
                "wifi" -> { if (Build.VERSION.SDK_INT >= 29) add(Settings.Panel.ACTION_WIFI); add(Settings.ACTION_WIFI_SETTINGS) }
                "internet" -> { if (Build.VERSION.SDK_INT >= 29) add(Settings.Panel.ACTION_INTERNET_CONNECTIVITY); add(Settings.ACTION_WIRELESS_SETTINGS) }
                "volume" -> { if (Build.VERSION.SDK_INT >= 29) add(Settings.Panel.ACTION_VOLUME); add(Settings.ACTION_SOUND_SETTINGS) }
                "nfc" -> { if (Build.VERSION.SDK_INT >= 29) add(Settings.Panel.ACTION_NFC); add(Settings.ACTION_NFC_SETTINGS) }
                "bluetooth" -> add(Settings.ACTION_BLUETOOTH_SETTINGS)
                "location" -> add(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                "battery" -> { add(Intent.ACTION_POWER_USAGE_SUMMARY); add(Settings.ACTION_BATTERY_SAVER_SETTINGS) }
                "display" -> add(Settings.ACTION_DISPLAY_SETTINGS)
                "airplane" -> add(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                "notifications" -> add("android.settings.NOTIFICATION_SETTINGS")
                "apps" -> add(Settings.ACTION_APPLICATION_SETTINGS)
            }
            add(Settings.ACTION_SETTINGS)
        }
        for (a in candidates) if (start(Intent(a))) return "Opened ${panel} settings."
        return "Couldn't open ${panel} settings."
    }

    // ---- device ----

    fun flashlight(on: Boolean): String {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "This phone has no flashlight."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    private fun stream(name: String?) = when (name) {
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        else -> AudioManager.STREAM_MUSIC
    }

    fun volume(action: String, level: Int?, streamName: String?): String {
        val s = stream(streamName)
        val label = streamName ?: "media"
        try {
            when (action) {
                "up" -> audio.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down" -> audio.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute" -> audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "unmute" -> audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                else -> {
                    val max = audio.getStreamMaxVolume(s)
                    audio.setStreamVolume(s, ((level ?: 50).coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
                }
            }
        } catch (e: SecurityException) {
            return "Android blocked changing the $label volume while Do Not Disturb is on."
        }
        val pct = audio.getStreamVolume(s) * 100 / maxOf(1, audio.getStreamMaxVolume(s))
        return "${label.replaceFirstChar { it.uppercase() }} volume is now $pct%."
    }

    fun hasDndAccess() = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun dnd(on: Boolean): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return "I need Do Not Disturb access. Allow it in More → Permissions & Diagnostics."
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return if (on) "Do Not Disturb is on." else "Do Not Disturb is off."
    }

    fun status(): String {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / maxOf(1, it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) }
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val snap = DeviceInfo.snapshot(context)
        val media = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxOf(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        val nm = context.getSystemService(NotificationManager::class.java)
        val dnd = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val ringer = when (audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }
        return buildString {
            append("Battery ${level ?: "?"}%")
            append(if (plugged) " (charging)" else "")
            append(" · ").append(if (snap.online) (if (snap.unmetered) "online (Wi-Fi)" else "online (mobile data)") else "offline")
            append(" · storage ${DeviceInfo.formatBytes(snap.freeStorageBytes)} free")
            append(" · media volume $media% · ringer $ringer")
            if (dnd) append(" · Do Not Disturb on")
        }
    }
}

/**
 * Keeps recent notifications so ZED can answer "what did I miss?". Only
 * active once the user grants notification access; nothing is stored on disk
 * and nothing is read unless the user asks.
 */
class ZedNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        toItem(this, sbn)?.let { item -> synchronized(recent) { recent.removeAll { it.key == item.key }; recent.addLast(item); while (recent.size > 200) recent.removeFirst() } }
        replyTarget(this, sbn)?.let { t -> synchronized(replyable) { replyable.remove(t.key); replyable[t.key] = t; while (replyable.size > 60) replyable.remove(replyable.keys.first()) } }
    }

    /** A conversation ZED can answer in place: a messaging notification with a reply box. */
    data class ReplyTarget(val key: String, val app: String, val who: String, val action: android.app.Notification.Action, val time: Long)

    data class Item(val key: String, val app: String, val title: String, val text: String, val time: Long)

    companion object {
        @Volatile private var instance: ZedNotificationListener? = null
        private val recent = ArrayDeque<Item>()
        private val replyable = LinkedHashMap<String, ReplyTarget>()

        private fun replyTarget(context: Context, sbn: StatusBarNotification): ReplyTarget? {
            if (sbn.packageName == context.packageName) return null
            val n = sbn.notification ?: return null
            val action = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } ?: return null
            val extras = n.extras
            val who = (extras.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
                ?.toString()?.replace(Regex("\\s*\\(\\d+ (?:new )?messages?\\)$"), "")?.trim().orEmpty()
            if (who.isBlank()) return null
            val app = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            return ReplyTarget(sbn.key, app, who, action, sbn.postTime)
        }

        /** Conversations from [who] (and [app], if given) that can be answered right now, newest first. */
        fun replyTargets(context: Context, who: String, app: String?): List<ReplyTarget> {
            val active = runCatching { instance?.activeNotifications?.mapNotNull { replyTarget(context, it) } }.getOrNull().orEmpty()
            val kept = synchronized(replyable) { replyable.values.toList() }
            val q = who.trim().lowercase()
            return (active + kept).distinctBy { it.key }
                .filter { t -> (app == null || t.app.contains(app, ignoreCase = true)) && t.who.lowercase().let { it.contains(q) || (q.contains(it) && it.length >= 3) } }
                .sortedByDescending { it.time }
        }

        /** Types [message] into the conversation's reply box and sends it. */
        fun sendReply(context: Context, target: ReplyTarget, message: String): Boolean = runCatching {
            val inputs = target.action.remoteInputs ?: return false
            val results = android.os.Bundle().apply { inputs.filter { it.allowFreeFormInput }.forEach { putCharSequence(it.resultKey, message) } }
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            android.app.RemoteInput.addResultsToIntent(inputs, intent, results)
            target.action.actionIntent.send(context, 0, intent)
            true
        }.getOrDefault(false)

        /** Recent messages from a person or app (newest first). */
        fun messagesFrom(context: Context, who: String?, app: String?, hours: Int = 24): List<Item> =
            recent(context, app, hours).filter { who == null || it.title.contains(who, ignoreCase = true) }

        fun hasAccess(context: Context): Boolean =
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun openSettings(context: Context) {
            val detail = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, android.content.ComponentName(context, ZedNotificationListener::class.java).flattenToString()) else null
            val generic = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            for (i in listOfNotNull(detail, generic)) {
                if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
            }
        }

        private fun toItem(context: Context, sbn: StatusBarNotification): Item? {
            if (sbn.packageName == context.packageName || sbn.isOngoing) return null
            val n = sbn.notification ?: return null
            if (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return null
            val extras = n.extras
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return null
            val app = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            return Item(sbn.key, app, title, text.take(300), sbn.postTime)
        }

        /** Current and recently posted notifications, newest first. */
        fun recent(context: Context, app: String?, hours: Int): List<Item> {
            val since = System.currentTimeMillis() - hours * 3_600_000L
            val active = runCatching { instance?.activeNotifications?.mapNotNull { toItem(context, it) } }.getOrNull().orEmpty()
            val buffered = synchronized(recent) { recent.toList() }
            return (active + buffered).distinctBy { it.key + it.time }.distinctBy { it.key }
                .filter { it.time >= since && (app == null || it.app.contains(app, ignoreCase = true)) }
                .sortedByDescending { it.time }
        }

        fun describe(items: List<Item>): String = items.take(25).joinToString("\n") {
            "• ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.time))} ${it.app}: ${it.title}${if (it.text.isNotBlank()) " — ${it.text}" else ""}"
        }
    }
}
