package com.example.familycall

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.UUID

private fun has(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Calls directly if permission was granted, otherwise opens the dialer with the number filled in. */
fun placeCall(context: Context, phone: String) {
    val uri = Uri.parse("tel:" + Uri.encode(phone))
    val action = if (has(context, Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
    context.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun sendSms(context: Context, numbers: List<String>, message: String): Boolean {
    if (!has(context, Manifest.permission.SEND_SMS)) return false
    return try {
        val sms: SmsManager =
            if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(message)
        numbers.forEach { n -> sms.sendMultipartTextMessage(n, null, parts, null, null) }
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Gets a fresh location (GPS and network), waiting up to [timeoutMs].
 * Falls back to the last known location, or null if there is none.
 */
@SuppressLint("MissingPermission")
fun fetchLocation(context: Context, timeoutMs: Long = 12000, onResult: (Location?) -> Unit) {
    val granted = has(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        has(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (!granted) { onResult(null); return }

    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val enabled = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
        try { lm.isProviderEnabled(it) } catch (e: Exception) { false }
    }
    val last = enabled.mapNotNull {
        try { lm.getLastKnownLocation(it) } catch (e: Exception) { null }
    }.maxByOrNull { it.time }
    if (enabled.isEmpty()) { onResult(last); return }

    val handler = Handler(Looper.getMainLooper())
    val listeners = mutableListOf<LocationListener>()
    var finished = false

    fun finish(loc: Location?) {
        if (finished) return
        finished = true
        listeners.forEach { try { lm.removeUpdates(it) } catch (_: Exception) {} }
        handler.removeCallbacksAndMessages(null)
        onResult(loc ?: last)
    }

    enabled.forEach { provider ->
        val listener = LocationListener { loc -> finish(loc) }
        listeners.add(listener)
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (_: Exception) {}
    }
    handler.postDelayed({ finish(null) }, timeoutMs)
}

fun mapLink(loc: Location): String =
    String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", loc.latitude, loc.longitude)

private fun who(store: Store) = store.senderName.ifBlank { "Your family member" }

fun buildLocationMessage(store: Store, loc: Location?): String =
    if (loc != null) "${who(store)} is sharing their location: ${mapLink(loc)}"
    else "${who(store)} tried to share their location, but it could not be found."

fun buildSosMessage(store: Store, loc: Location?): String =
    "SOS! ${who(store)} needs help now. " +
        (if (loc != null) "Location: ${mapLink(loc)}" else "Location unavailable - please call them.")

/** Copies the chosen photo into the app's private storage, shrunk so it stays small. */
fun savePhoto(context: Context, uri: Uri): String? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val file = File(context.filesDir, "photo_${UUID.randomUUID()}.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file.absolutePath
    } catch (e: Exception) {
        null
    }
}

/** Vibration + a short beep so the user knows something happened without reading. */
fun feedback(context: Context) {
    try {
        val vibrator: Vibrator =
            if (Build.VERSION.SDK_INT >= 31)
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(400)
        }
    } catch (_: Exception) {}
    try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_ACK, 300)
    } catch (_: Exception) {}
}
