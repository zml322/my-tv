package com.lizongying.mytv

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class IptvChannel(
    val name: String,
    val url: String,
    val logo: String,
    val group: String,
)

object IptvSource {
    private const val TAG = "IptvSource"

    private val M3U_URLS = listOf(
        "https://live.zbds.top/tv/iptv4.m3u",
        "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv6.m3u",
        "https://raw.githubusercontent.com/YueChan/Live/refs/heads/main/APTV.m3u",
    )

    private const val CACHE_FILE = "iptv_cache.json"
    private const val CACHE_EXPIRY_MS = 6 * 60 * 60 * 1000L // 6 hours

    private var channels: List<IptvChannel> = emptyList()
    private var nameToUrl: Map<String, IptvChannel> = emptyMap()
    private var loaded = false

    suspend fun init(context: android.content.Context) {
        if (loaded) return

        // Try cache first
        val cacheFile = File(context.cacheDir, CACHE_FILE)
        if (cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified()) < CACHE_EXPIRY_MS) {
            try {
                val cached = cacheFile.readText()
                channels = parseJson(cached)
                buildMap()
                if (channels.isNotEmpty()) {
                    loaded = true
                    Log.i(TAG, "Loaded ${channels.size} channels from cache")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cache read failed: ${e.message}")
            }
        }

        // Fetch fresh data
        withContext(Dispatchers.IO) {
            for (url in M3U_URLS) {
                try {
                    val content = fetchUrl(url)
                    if (content.isNotEmpty()) {
                        channels = parseM3u(content)
                        if (channels.isNotEmpty()) {
                            buildMap()
                            loaded = true
                            // Save cache
                            try {
                                cacheFile.writeText(toJson())
                            } catch (e: Exception) {
                                Log.w(TAG, "Cache write failed: ${e.message}")
                            }
                            Log.i(TAG, "Loaded ${channels.size} channels from $url")
                            return@withContext
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch $url: ${e.message}")
                }
            }
        }
    }

    fun getUrl(channelName: String): String? {
        return nameToUrl[channelName]?.url
    }

    fun getLogo(channelName: String): Any {
        return nameToUrl[channelName]?.logo ?: ""
    }

    fun getUrlsByGroup(group: String): List<IptvChannel> {
        return channels.filter { it.group == group }
    }

    fun isLoaded(): Boolean = loaded

    fun getAllChannels(): List<IptvChannel> = channels

    private fun buildMap() {
        val map = mutableMapOf<String, IptvChannel>()
        for (ch in channels) {
            // Also index by name without suffixes and aliases
            val keys = mutableListOf(ch.name)
            // Add common aliases
            when (ch.name) {
                "CCTV1" -> keys.addAll(listOf("CCTV-1", "CCTV1 综合"))
                "CCTV2" -> keys.addAll(listOf("CCTV-2", "CCTV2 财经"))
                "CCTV3" -> keys.addAll(listOf("CCTV-3", "CCTV3 综艺"))
                "CCTV4" -> keys.addAll(listOf("CCTV-4", "CCTV4 中文国际"))
                "CCTV5" -> keys.addAll(listOf("CCTV-5", "CCTV5 体育"))
                "CCTV5+" -> keys.addAll(listOf("CCTV5+ 体育赛事"))
                "CCTV6" -> keys.addAll(listOf("CCTV-6", "CCTV6 电影"))
                "CCTV7" -> keys.addAll(listOf("CCTV-7", "CCTV7 国防军事"))
                "CCTV8" -> keys.addAll(listOf("CCTV-8", "CCTV8 电视剧"))
                "CCTV9" -> keys.addAll(listOf("CCTV-9", "CCTV9 纪录"))
                "CCTV10" -> keys.addAll(listOf("CCTV-10", "CCTV10 科教"))
                "CCTV11" -> keys.addAll(listOf("CCTV-11", "CCTV11 戏曲"))
                "CCTV12" -> keys.addAll(listOf("CCTV-12", "CCTV12 社会与法"))
                "CCTV13" -> keys.addAll(listOf("CCTV-13", "CCTV13 新闻"))
                "CCTV14" -> keys.addAll(listOf("CCTV-14", "CCTV14 少儿"))
                "CCTV15" -> keys.addAll(listOf("CCTV-15", "CCTV15 音乐"))
                "CCTV16" -> keys.addAll(listOf("CCTV16 奥林匹克"))
                "CCTV17" -> keys.addAll(listOf("CCTV-17", "CCTV17 农业农村"))
            }
            for (key in keys) {
                if (key !in map) {
                    map[key] = ch
                }
            }
        }
        nameToUrl = map
    }

    private fun parseM3u(content: String): List<IptvChannel> {
        val result = mutableListOf<IptvChannel>()
        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                val name = extractTag(line, "tvg-name") ?: extractName(line)
                val logo = extractTag(line, "tvg-logo") ?: ""
                val group = extractTag(line, "group-title") ?: ""

                // Next non-empty, non-comment line is the URL
                i++
                while (i < lines.size && (lines[i].isBlank() || lines[i].trim().startsWith("#"))) {
                    i++
                }
                val url = if (i < lines.size) lines[i].trim() else ""

                if (name != null && url.isNotEmpty() && url.startsWith("http")) {
                    result.add(IptvChannel(name = name, url = url, logo = logo, group = group))
                }
            }
            i++
        }
        return result
    }

    private fun extractTag(line: String, tag: String): String? {
        val regex = Regex("""$tag="([^"]*)"""")
        return regex.find(line)?.groupValues?.get(1)
    }

    private fun extractName(line: String): String? {
        // Name is after the last comma
        val lastComma = line.lastIndexOf(",")
        if (lastComma >= 0 && lastComma < line.length - 1) {
            return line.substring(lastComma + 1).trim()
        }
        return null
    }

    private fun fetchUrl(urlStr: String): String {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")

        return try {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            reader.close()
            connection.disconnect()
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "fetchUrl error: ${e.message}")
            ""
        }
    }

    private fun toJson(): String {
        val arr = org.json.JSONArray()
        for (ch in channels) {
            val obj = JSONObject()
            obj.put("name", ch.name)
            obj.put("url", ch.url)
            obj.put("logo", ch.logo)
            obj.put("group", ch.group)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun parseJson(json: String): List<IptvChannel> {
        val result = mutableListOf<IptvChannel>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(
                IptvChannel(
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    logo = obj.optString("logo", ""),
                    group = obj.optString("group", ""),
                )
            )
        }
        return result
    }
}
