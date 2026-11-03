package com.nuliz.ads

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val KEY_ADS = "ads"
    private const val KEY_MALWARE = "malware"
    private const val KEY_NOTIFY = "notify_each_block"
    private const val KEY_API = "ai_key"
    private const val KEY_CHAT = "ai_chat"
    private const val KEY_CHATS = "ai_chats"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences("nuliz", Context.MODE_PRIVATE)
        }
    }

    val ads get() = sp.getLong(KEY_ADS, 0)
    val malware get() = sp.getLong(KEY_MALWARE, 0)

    var notifyEachBlock: Boolean
        get() = sp.getBoolean(KEY_NOTIFY, true)
        set(value) = sp.edit().putBoolean(KEY_NOTIFY, value).apply()

    var apiKey: String?
        get() = sp.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
        set(value) = sp.edit().apply { if (value.isNullOrBlank()) remove(KEY_API) else putString(KEY_API, value) }.apply()

    var chatJson: String?
        get() = sp.getString(KEY_CHAT, null)
        set(value) = sp.edit().apply { if (value == null) remove(KEY_CHAT) else putString(KEY_CHAT, value) }.apply()

    var chatsJson: String?
        get() = sp.getString(KEY_CHATS, null)
        set(value) = sp.edit().apply { if (value == null) remove(KEY_CHATS) else putString(KEY_CHATS, value) }.apply()

    fun saveCounts(ads: Long, malware: Long) =
        sp.edit().putLong(KEY_ADS, ads).putLong(KEY_MALWARE, malware).apply()
}
