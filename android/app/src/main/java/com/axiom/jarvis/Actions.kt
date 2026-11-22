package com.axiom.jarvis

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Executes [Cmd]s on the phone. Returns the spoken reply, or null to hand off to the AI. */
class Actions(private val a: Activity) {

    fun run(text: String): String? {
        val cmd = Commands.parse(text) ?: return null
        return try { exec(cmd) } catch (e: ActivityNotFoundException) { "No app on this phone can do that." }
        catch (e: Exception) { "That failed: ${e.message}" }
    }

    private fun start(i: Intent) = a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun exec(c: Cmd): String? = when (c) {
        is Cmd.Open -> openApp(c.app)
        is Cmd.Call -> call(c.who)
        is Cmd.Alarm -> {
            start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, c.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, c.minute).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "AXIOM"))
            "Alarm set for %02d:%02d.".format(c.hour, c.minute)
        }
        is Cmd.Timer -> {
            start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, c.seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "AXIOM"))
            "Timer running: ${human(c.seconds)}."
        }
        is Cmd.Torch -> torch(c.on)
        is Cmd.Search -> {
            try { start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, c.query)) }
            catch (e: ActivityNotFoundException) { start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(c.query)))) }
            "Searching the web for ${c.query}."
        }
        is Cmd.Navigate -> { start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(c.place)))); "Plotting a route to ${c.place}." }
        is Cmd.Play -> {
            val yt = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", c.query)
            try { start(yt) } catch (e: ActivityNotFoundException) {
                start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(c.query))))
            }
            "Playing ${c.query}."
        }
        Cmd.Time -> "It's " + SimpleDateFormat("h:mm a, EEEE d MMMM", Locale.getDefault()).format(Date()) + "."
        Cmd.Battery -> {
            val b = a.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val pct = (b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0) * 100 / (b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100)
            val chg = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            "Battery at $pct%" + if (chg) ", charging." else "."
        }
    }

    private fun human(s: Int) = when {
        s >= 3600 && s % 3600 == 0 -> "${s / 3600} hour" + if (s / 3600 > 1) "s" else ""
        s >= 60 && s % 60 == 0 -> "${s / 60} minute" + if (s / 60 > 1) "s" else ""
        else -> "$s seconds"
    }

    private val settings = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS, "wi-fi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS, "settings" to Settings.ACTION_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS, "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS, "sound" to Settings.ACTION_SOUND_SETTINGS,
        "hotspot" to Settings.ACTION_WIRELESS_SETTINGS, "mobile data" to Settings.ACTION_DATA_ROAMING_SETTINGS,
    )

    private fun openApp(name: String): String? {
        val q = name.lowercase().removeSuffix(" settings").trim()
        settings[q]?.let { start(Intent(it)); return "Opening ${q} settings." }
        val pm = a.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val squash = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]"), "") }
        val target = squash(q)
        val hit = apps.firstOrNull { squash(it.first) == target }
            ?: apps.firstOrNull { squash(it.first).startsWith(target) }
            ?: apps.firstOrNull { squash(it.first).contains(target) }
            ?: return null  // unknown app: let the AI answer instead
        start(pm.getLaunchIntentForPackage(hit.second) ?: return null)
        return "Opening ${hit.first}."
    }

    private fun call(who: String): String {
        val digits = who.replace(Regex("[^0-9+]"), "")
        if (digits.length >= 3) {
            start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))); return "Dialing $digits."
        }
        if (a.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 42)
            return "I need access to contacts. Allow it, then ask me again."
        }
        val cur = a.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$who%"), null)
        cur?.use {
            if (it.moveToFirst()) {
                val name = it.getString(0); val num = it.getString(1)
                start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))))
                return "Dialing $name. Tap call to connect."
            }
        }
        return "I couldn't find $who in your contacts."
    }

    private fun torch(on: Boolean): String {
        val cm = a.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "This phone has no flashlight I can reach."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }
}
