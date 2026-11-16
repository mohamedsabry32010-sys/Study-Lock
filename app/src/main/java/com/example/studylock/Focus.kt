package com.example.studylock

import android.content.Context
import android.content.SharedPreferences

object Focus {
    val BLOCKED = setOf(
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",   // TikTok
        "com.instagram.android", "com.instagram.lite",            // Instagram
        "com.snapchat.android",                                   // Snapchat
        "com.facebook.katana", "com.facebook.lite",               // Facebook
        "com.google.android.youtube"                              // YouTube
    )
    const val YOUTUBE = "com.google.android.youtube"

    fun p(c: Context): SharedPreferences = c.getSharedPreferences("focus", Context.MODE_PRIVATE)
    fun lockUntil(c: Context) = p(c).getLong("lockUntil", 0L)
    fun breakUntil(c: Context) = p(c).getLong("breakUntil", 0L)
    fun allowYoutube(c: Context) = p(c).getBoolean("allowYoutube", false)

    /** مقفول = الجلسة شغالة ومفيش استراحة */
    fun isLocked(c: Context): Boolean {
        val now = System.currentTimeMillis()
        return now < lockUntil(c) && now >= breakUntil(c)
    }
}
