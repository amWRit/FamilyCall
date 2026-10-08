package com.example.familycall

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Contact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val photoPath: String? = null,
    val shareLocation: Boolean = false,
    val sos: Boolean = false
)

/** Everything is stored on the phone itself. No server, no database. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("familycall", Context.MODE_PRIVATE)

    fun loadContacts(): List<Contact> {
        val raw = prefs.getString("contacts", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Contact(
                id = o.getString("id"),
                name = o.getString("name"),
                phone = o.getString("phone"),
                photoPath = o.optString("photo").ifBlank { null },
                shareLocation = o.optBoolean("loc", false),
                sos = o.optBoolean("sos", false)
            )
        }
    }

    fun saveContacts(list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("phone", c.phone)
                    .put("photo", c.photoPath ?: "")
                    .put("loc", c.shareLocation)
                    .put("sos", c.sos)
            )
        }
        prefs.edit().putString("contacts", arr.toString()).apply()
    }

    /** Name that appears at the start of the messages, e.g. "Mom". */
    var senderName: String
        get() = prefs.getString("senderName", "") ?: ""
        set(v) = prefs.edit().putString("senderName", v).apply()

    /** Who receives the "share location" message (one number). */
    var locationRecipient: String
        get() = prefs.getString("locRecipient", "") ?: ""
        set(v) = prefs.edit().putString("locRecipient", v).apply()

    /** Who receives SOS (one or more numbers, comma separated). The first is also auto-dialed. */
    var sosRecipients: String
        get() = prefs.getString("sosRecipients", "") ?: ""
        set(v) = prefs.edit().putString("sosRecipients", v).apply()

    fun sosNumbers(): List<String> =
        sosRecipients.split(",").map { it.trim() }.filter { it.isNotBlank() }
}
