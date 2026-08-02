package com.lizongying.mytv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class IptvChannel(
    val name: String,
    val url: String,
    val logo: String,
    val group: String,
    val provider: String = "",
)

data class IptvRefreshResult(
    val channelCount: Int,
    val providerCount: Int,
    val refreshed: Boolean,
)

/**
 * Keeps several public IPTV indexes as fallbacks for official broadcaster players.
 *
 * Every index is downloaded and merged.  The previous implementation stopped after the first
 * successful index and retained only one URL for each channel, which made a single dead link
 * indistinguishable from a missing channel.
 */
object IptvSource {
    private const val TAG = "IptvSource"

    private val M3U_URLS = linkedMapOf(
        "直播电视" to "https://live.zbds.top/tv/iptv4.m3u",
        "fanmingming" to "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv6.m3u",
        "APTV" to "https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u",
    )

    private const val CACHE_FILE = "iptv_sources_v4.json"
    const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_URLS_PER_CHANNEL = 8

    private val refreshMutex = Mutex()
    private var channels: List<IptvChannel> = emptyList()
    private var nameToChannels: Map<String, List<IptvChannel>> = emptyMap()
    private var loaded = false
    private var updatedAt = 0L

    suspend fun init(context: Context): IptvRefreshResult = refresh(context, force = false)

    suspend fun refresh(context: Context, force: Boolean): IptvRefreshResult = refreshMutex.withLock {
        val cacheFile = File(context.filesDir, CACHE_FILE)
        if (!loaded) {
            loadCache(cacheFile)
        }

        val cacheFresh = channels.isNotEmpty() &&
            System.currentTimeMillis() - updatedAt < REFRESH_INTERVAL_MS
        if (!force && cacheFresh) {
            loaded = true
            return@withLock currentResult(refreshed = false)
        }

        val fetched = withContext(Dispatchers.IO) {
            coroutineScope {
                M3U_URLS.map { (provider, url) ->
                    async {
                        try {
                            val parsed = parseM3u(fetchUrl(url), provider)
                            Log.i(TAG, "Loaded ${parsed.size} channels from $provider")
                            parsed
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to fetch $provider ($url): ${e.message}")
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
        }

        if (fetched.isNotEmpty()) {
            channels = deduplicate(fetched)
            updatedAt = System.currentTimeMillis()
            buildMap()
            loaded = true
            saveCache(cacheFile)
            Log.i(
                TAG,
                "Merged ${channels.size} unique streams from ${providerCount()} providers",
            )
            return@withLock currentResult(refreshed = true)
        }

        // A temporary network outage must never erase the last known working set.
        if (channels.isNotEmpty()) {
            loaded = true
            Log.w(TAG, "Refresh failed; keeping ${channels.size} cached streams")
        }
        currentResult(refreshed = false)
    }

    fun getUrls(vararg channelNames: String): List<IptvChannel> {
        val result = linkedMapOf<String, IptvChannel>()
        channelNames.forEach { name ->
            candidateKeys(name).forEach { key ->
                nameToChannels[key].orEmpty().forEach { channel ->
                    if (result.size < MAX_URLS_PER_CHANNEL) {
                        result.putIfAbsent(channel.url, channel)
                    }
                }
            }
        }
        return result.values.toList()
    }

    fun getUrl(channelName: String): String? = getUrls(channelName).firstOrNull()?.url

    fun getLogo(channelName: String): Any = getUrls(channelName).firstOrNull()?.logo ?: ""

    fun getUrlsByGroup(group: String): List<IptvChannel> = channels.filter { it.group == group }

    fun providerForUrl(url: String): String =
        channels.firstOrNull { it.url == url }?.provider.orEmpty()

    fun isLoaded(): Boolean = loaded

    fun getAllChannels(): List<IptvChannel> = channels

    fun lastUpdatedAt(): Long = updatedAt

    private fun currentResult(refreshed: Boolean) = IptvRefreshResult(
        channelCount = channels.size,
        providerCount = providerCount(),
        refreshed = refreshed,
    )

    private fun providerCount(): Int = channels.map { it.provider }.filter { it.isNotEmpty() }.distinct().size

    private fun buildMap() {
        val map = mutableMapOf<String, MutableList<IptvChannel>>()
        channels.forEach { channel ->
            candidateKeys(channel.name).forEach { key ->
                map.getOrPut(key) { mutableListOf() }.add(channel)
            }
        }

        nameToChannels = map.mapValues { (_, value) ->
            value.distinctBy { it.url }.sortedByDescending { officialDomainScore(it.url) }
        }
    }

    private fun candidateKeys(name: String): Set<String> {
        val normalized = normalizeName(name)
        val keys = linkedSetOf(normalized)

        val cctvMatch = Regex("^CCTV(\\d{1,2})(.*)$").find(normalized)
        if (cctvMatch != null) {
            keys += "CCTV${cctvMatch.groupValues[1]}"
        }

        when (normalized) {
            "上海卫视" -> keys += "东方卫视"
            "东方卫视" -> keys += "上海卫视"
            "CGTN西班牙语", "CGTN西语频道" -> keys += "CGTN西语"
            "CGTN阿拉伯语", "CGTN阿语频道" -> keys += "CGTN阿语"
            "CGTN法语频道" -> keys += "CGTN法语"
            "CGTN俄语频道" -> keys += "CGTN俄语"
            "CGTN纪录频道" -> keys += "CGTN纪录"
            "凤凰卫视资讯台" -> keys += "凤凰资讯"
            "凤凰卫视中文台" -> keys += "凤凰中文"
            "凤凰卫视香港台" -> keys += "凤凰香港"
        }
        return keys
    }

    private fun normalizeName(value: String): String = value
        .trim()
        .uppercase()
        .replace("CCTV-", "CCTV")
        .replace("中央电视台", "CCTV")
        .replace(Regex("[\\s·_()（）【】\\[\\]]"), "")
        .replace(Regex("(高清|超清|蓝光|HD|4M|8M|频道)$"), "")

    private fun officialDomainScore(url: String): Int {
        val officialDomains = listOf(
            "cctv.com", "cntv.cn", "cgtn.com", "mgtv.com", "qing.mgtv.com",
            "cztv.com", "cztvcloud.com", "jstv.com", "hbtv.com.cn", "iqilu.com",
            "gdtv.cn", "gxntv.com", "liangtv.cn", "hljtv.com", "xntv.tv",
            "btzx.com.cn", "cnr.cn",
        )
        return if (officialDomains.any { domain ->
                runCatching { URL(url).host.endsWith(domain) }.getOrDefault(false)
            }
        ) 1 else 0
    }

    private fun deduplicate(items: List<IptvChannel>): List<IptvChannel> = items
        .distinctBy { "${normalizeName(it.name)}|${it.url}" }

    private fun parseM3u(content: String, provider: String): List<IptvChannel> {
        val result = mutableListOf<IptvChannel>()
        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                val name = extractTag(line, "tvg-name")?.takeIf { it.isNotBlank() }
                    ?: extractName(line)
                val logo = extractTag(line, "tvg-logo") ?: ""
                val group = extractTag(line, "group-title") ?: ""

                i++
                while (i < lines.size &&
                    (lines[i].isBlank() || lines[i].trim().startsWith("#"))
                ) {
                    i++
                }
                val url = if (i < lines.size) lines[i].trim() else ""

                if (!name.isNullOrBlank() && url.startsWith("http")) {
                    result += IptvChannel(name, url, logo, group, provider)
                }
            }
            i++
        }
        return result
    }

    private fun extractTag(line: String, tag: String): String? =
        Regex("""$tag="([^"]*)"""").find(line)?.groupValues?.get(1)

    private fun extractName(line: String): String? {
        val lastComma = line.lastIndexOf(',')
        return if (lastComma in 0 until line.lastIndex) line.substring(lastComma + 1).trim() else null
    }

    private fun fetchUrl(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "MyTV/official-fallback Android")
        connection.instanceFollowRedirects = true

        return try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                buildString {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        append(line).append('\n')
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun saveCache(file: File) {
        try {
            val root = JSONObject().put("updatedAt", updatedAt)
            val array = JSONArray()
            channels.forEach { channel ->
                array.put(
                    JSONObject()
                        .put("name", channel.name)
                        .put("url", channel.url)
                        .put("logo", channel.logo)
                        .put("group", channel.group)
                        .put("provider", channel.provider),
                )
            }
            root.put("channels", array)
            file.writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Cache write failed: ${e.message}")
        }
    }

    private fun loadCache(file: File) {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            updatedAt = root.optLong("updatedAt", file.lastModified())
            channels = parseJson(root.optJSONArray("channels") ?: JSONArray())
            if (channels.isNotEmpty()) {
                buildMap()
                loaded = true
                Log.i(TAG, "Loaded ${channels.size} cached streams")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache read failed: ${e.message}")
        }
    }

    private fun parseJson(array: JSONArray): List<IptvChannel> = buildList {
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            add(
                IptvChannel(
                    name = item.getString("name"),
                    url = item.getString("url"),
                    logo = item.optString("logo"),
                    group = item.optString("group"),
                    provider = item.optString("provider", "cache"),
                ),
            )
        }
    }
}
