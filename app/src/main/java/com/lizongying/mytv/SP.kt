package com.lizongying.mytv

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

object SP {
    // Name of the sp file TODO Should use a meaningful name and do migrations
    private const val SP_FILE_NAME = "MainActivity"

    // If Change channel with up and down in reversed order or not
    private const val KEY_CHANNEL_REVERSAL = "channel_reversal"

    // If use channel num to select channel or not
    private const val KEY_CHANNEL_NUM = "channel_num"

    private const val KEY_TIME = "time"

    // If start app on device boot or not
    private const val KEY_BOOT_STARTUP = "boot_startup"

    private const val KEY_GRID = "grid"

    // Position in list of the selected channel item
    private const val KEY_POSITION = "position"

    // guid
    private const val KEY_GUID = "guid"

    private const val KEY_PREFERRED_SOURCE_PREFIX = "preferred_source_"
    private const val KEY_STABLE_SOURCE_PREFIX = "stable_source_"
    private const val KEY_SOURCE_STARTUP_PREFIX = "source_startup_"
    private const val KEY_SOURCE_FAILURE_PREFIX = "source_failure_"

    private lateinit var sp: SharedPreferences

    /**
     * The method must be invoked as early as possible(At least before using the keys)
     */
    fun init(context: Context) {
        sp = context.getSharedPreferences(SP_FILE_NAME, Context.MODE_PRIVATE)
    }

    var channelReversal: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_REVERSAL, false)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_REVERSAL, value).apply()

    var channelNum: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_NUM, true)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_NUM, value).apply()

    var time: Boolean
        get() = sp.getBoolean(KEY_TIME, true)
        set(value) = sp.edit().putBoolean(KEY_TIME, value).apply()

    var bootStartup: Boolean
        get() = sp.getBoolean(KEY_BOOT_STARTUP, false)
        set(value) = sp.edit().putBoolean(KEY_BOOT_STARTUP, value).apply()

    var grid: Boolean
        get() = sp.getBoolean(KEY_GRID, false)
        set(value) = sp.edit().putBoolean(KEY_GRID, value).apply()

    var itemPosition: Int
        get() = sp.getInt(KEY_POSITION, 0)
        set(value) = sp.edit().putInt(KEY_POSITION, value).apply()

    var guid: String
        get() = sp.getString(KEY_GUID, "") ?: ""
        set(value) = sp.edit().putString(KEY_GUID, value).apply()

    fun preferredSource(channelName: String): String =
        sp.getString(KEY_PREFERRED_SOURCE_PREFIX + channelName, "").orEmpty()

    fun setPreferredSource(channelName: String, source: String) {
        sp.edit().putString(KEY_PREFERRED_SOURCE_PREFIX + channelName, source).apply()
    }

    fun stableSource(channelName: String): String =
        sp.getString(KEY_STABLE_SOURCE_PREFIX + channelName, "").orEmpty()

    fun setStableSource(channelName: String, source: String) {
        sp.edit()
            .putString(KEY_STABLE_SOURCE_PREFIX + channelName, source)
            .putString(KEY_PREFERRED_SOURCE_PREFIX + channelName, source)
            .apply()
    }

    fun clearStableSource(channelName: String) {
        sp.edit().remove(KEY_STABLE_SOURCE_PREFIX + channelName).apply()
    }

    fun sourceStartupMs(channelName: String, source: String): Long =
        sp.getLong(sourceMetricKey(KEY_SOURCE_STARTUP_PREFIX, channelName, source), -1L)

    fun sourceFailureCount(channelName: String, source: String): Int =
        sp.getInt(sourceMetricKey(KEY_SOURCE_FAILURE_PREFIX, channelName, source), 0)

    /** Keep a smoothed first-frame time so a single slow launch does not reorder every source. */
    fun recordSourceStartup(channelName: String, source: String, elapsedMs: Long) {
        if (channelName.isBlank() || source.isBlank()) return
        val startupKey = sourceMetricKey(KEY_SOURCE_STARTUP_PREFIX, channelName, source)
        val failureKey = sourceMetricKey(KEY_SOURCE_FAILURE_PREFIX, channelName, source)
        val current = sp.getLong(startupKey, -1L)
        val sample = elapsedMs.coerceIn(100L, 60_000L)
        val smoothed = if (current < 0L) sample else (current * 3L + sample) / 4L
        sp.edit()
            .putLong(startupKey, smoothed)
            .putInt(failureKey, 0)
            .apply()
    }

    fun recordSourceFailure(channelName: String, source: String) {
        if (channelName.isBlank() || source.isBlank()) return
        val key = sourceMetricKey(KEY_SOURCE_FAILURE_PREFIX, channelName, source)
        sp.edit().putInt(key, (sp.getInt(key, 0) + 1).coerceAtMost(10)).apply()
    }

    private fun sourceMetricKey(prefix: String, channelName: String, source: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${channelName.trim()}|$source".toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return prefix + digest
    }
}
