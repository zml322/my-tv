package com.lizongying.mytv

import java.net.URI
import java.security.MessageDigest

/** Official broadcaster-owned playback entries verified in August 2026. */
object OfficialLiveSources {
    private const val WEB_PREFIX = "official-web:"
    private const val HEBEI_SATELLITE = "official-signed:hebei-satellite"
    private const val HEBEI_LIVE_URI = "/jishi/weishipindao.m3u8"
    private const val HEBEI_LIVE_KEY = "k5m9p2x8r4b3"
    private const val HEBEI_TOKEN_LIFETIME_SECONDS = 2 * 60 * 60

    private val directStreams = mapOf(
        // 河北台官网公开接口返回该 HLS，并由官网按当前时间生成两小时签名。
        "河北卫视" to listOf(HEBEI_SATELLITE),
        "延边卫视" to listOf(
            "https://srs.iyb983.cn/video/CYS/index.m3u8",
        ),
        "安多卫视" to listOf(
            "https://liveout.xntv.tv/a65jur/96iln2.m3u8",
        ),
        "内蒙古蒙语卫视" to listOf(
            "https://livestream-bt.nmtv.cn/nmtv/2315general.m3u8" +
                "?txSecret=4971666599ef9411629213c9a300bf66&txTime=771EF880",
        ),
        "山东教育卫视" to listOf(
            "https://test1.live.sdetv.com.cn/live/dianshizhibo/playlist.m3u8",
        ),
        "山东教育" to listOf(
            "https://test1.live.sdetv.com.cn/live/dianshizhibo/playlist.m3u8",
        ),
    )

    /** Public Yangshipin television pages, restored from the project's former YSP mapping. */
    private val yangshipinPids = mutableMapOf(
        "CCTV1" to "600001859",
        "CCTV6" to "600108442",
        "CCTV8" to "600001803",
        "CCTV9" to "600004078",
        "CCTV13" to "600001811",
        "CCTV14" to "600001809",
        "东方卫视" to "600002483",
        "湖南卫视" to "600002475",
        "湖北卫视" to "600002508",
        "辽宁卫视" to "600002505",
        "江苏卫视" to "600002521",
        "江西卫视" to "600002503",
        "山东卫视" to "600002513",
        "广东卫视" to "600002485",
        "广西卫视" to "600002509",
        "重庆卫视" to "600002531",
        "河南卫视" to "600002525",
        "河北卫视" to "600002493",
        "贵州卫视" to "600002490",
        "北京卫视" to "600002309",
        "黑龙江卫视" to "600002498",
        "浙江卫视" to "600002520",
        "安徽卫视" to "600002532",
        "深圳卫视" to "600002481",
        "四川卫视" to "600002516",
        "东南卫视" to "600002484",
        "海南卫视" to "600002506",
        "天津卫视" to "600152137",
        "新疆卫视" to "600152138",
        "吉林卫视" to "600190405",
        "云南卫视" to "600190402",
        "陕西卫视" to "600190400",
        "山西卫视" to "600190407",
        "甘肃卫视" to "600190408",
        "青海卫视" to "600190406",
        "宁夏卫视" to "600190737",
        "内蒙古卫视" to "600190401",
        "西藏卫视" to "600190403",
    )

    /** Merge the current public channel list served by Yangshipin's TV page configuration. */
    @Synchronized
    fun updateYangshipinChannel(channelName: String, pid: String): Boolean {
        if (!pid.matches(Regex("^\\d{9}$"))) return false
        val normalizedName = when (val value = normalize(channelName)) {
            "福建东南卫视" -> "东南卫视"
            "上海卫视" -> "东方卫视"
            else -> value
        }
        if (!normalizedName.endsWith("卫视") && !normalizedName.startsWith("CCTV")) return false
        val changed = yangshipinPids[normalizedName] != pid
        yangshipinPids[normalizedName] = pid
        return changed
    }

    private val officialPages: Map<String, String> = buildMap {
        (1..17).forEach { channel ->
            put("CCTV$channel", "https://tv.cctv.com/live/cctv$channel/m/")
        }
        put("CCTV5+", "https://tv.cctv.com/live/cctv5plus/m/")
        put("风云足球", "https://sports.cctv.com/live/04/index.shtml")
        put("高尔夫网球", "https://sports.cctv.com/live/04/index.shtml")
        put("央视台球", "https://sports.cctv.com/live/waicai11/index.shtml")
        put("东方卫视", "https://live.kankanews.com/huikan?id=1")
        put("上海卫视", "https://live.kankanews.com/huikan?id=1")
        put("湖南卫视", "https://live.mgtv.com/")
        put("河南卫视", "https://static.hntv.tv/kds/#/")
        put("江苏卫视", "https://live.jstv.com/")
        put("湖北卫视", "https://news.hbtv.com.cn/app/tv/431")
        put("山东卫视", "https://v.iqilu.com/live/sdtv/?isLink=1")
        put("浙江卫视", "https://www.cztv.com/liveTV")
        put("广西卫视", "https://tv.gxtv.cn/")
        put("河北卫视", "https://www.hebtv.com/19/19js/st/xdszb/index.shtml?index=0")
        put("北京卫视", "https://www.btime.com/btv/btvws_index")
        put("贵州卫视", "https://www.gzstv.com/tv/ch01")
        put("云南卫视", "https://www.yntv.cn/live.html")
        // 海南台 channel API identifies 海南卫视 as id=13 (id=1 is 海南自贸).
        put("海南卫视", "https://www.hnntv.cn/live.html?channelId=13&playType=livePlay")
        put("安徽卫视", "https://www.ahtv.cn/folder9000/folder20193?channelIndex=0")
        put("四川卫视", "https://www.sctv.com/live/list")
        put("东南卫视", "https://www.setv.fjtv.net/live/")
        put("宁夏卫视", "https://www.nxtv.com.cn/19/19kds/dsp/")
        put("兵团卫视", "https://www.btzx.com.cn/2024new/new_zhibo/index.shtml")
        put("内蒙古卫视", "https://www.nmtv.cn/liveTv")
        put("海峡卫视", "https://live.fjtv.net/hxtv/")
        put("三沙卫视", "https://www.hnntv.cn/live.html?channelId=5&playType=livePlay")
        put("大湾区卫视", "https://www.gdtv.cn/tvChannelDetail/51")
        put("康巴卫视", "https://www.kangbatv.com/zb_22587/")
        put("CETV1", "https://www.centv.cn/cetv1")
        put("CGTN", "https://www.cgtn.com/tv")
    }

    fun sourcesFor(vararg names: String): List<String> {
        val result = linkedSetOf<String>()
        names.forEach { rawName ->
            val name = normalize(rawName)
            yangshipinPids[name]?.let { pid ->
                result += webUrl("https://www.yangshipin.cn/tv/home?pid=$pid")
            }
            directStreams[name].orEmpty().forEach(result::add)
            officialPages[name]?.let { result += webUrl(it) }
        }
        return result.toList()
    }

    fun isWeb(url: String): Boolean = url.startsWith(WEB_PREFIX)

    fun unwrapWeb(url: String): String = url.removePrefix(WEB_PREFIX)

    /** Some official players expose the live page but require an account before video starts. */
    fun requiresLogin(url: String): Boolean = isWeb(url) &&
        unwrapWeb(url).startsWith("https://www.btime.com/btv/")

    fun sourceLabel(
        url: String,
        index: Int,
        total: Int,
        savedProvider: String = "",
        channelName: String = "",
    ): String {
        val provider = savedProvider.ifBlank { sourceName(url) }.ifBlank {
            IptvSource.providerForUrl(url).ifBlank { "公共 IPTV" }
        }
        val officialPrefix = channelName.substringBefore(' ').ifBlank { "频道" } + "官网"
        return when {
            isYangshipin(url) -> "央视频｜官方直播"
            isWeb(url) -> "$officialPrefix｜$provider"
            isOfficialDirect(url) -> "$officialPrefix｜$provider · 直连"
            else -> "备用 ${index + 1}/$total｜$provider"
        }
    }

    private fun isYangshipin(url: String): Boolean = isWeb(url) &&
        unwrapWeb(url).contains("yangshipin.cn/")

    fun sourceName(url: String): String {
        if (url == HEBEI_SATELLITE) return "河北广播电视台"
        val resolvedUrl = if (isWeb(url)) unwrapWeb(url) else url
        val host = runCatching { URI(resolvedUrl).host.orEmpty() }.getOrDefault("")
        return SOURCE_NAMES_BY_DOMAIN.entries.firstOrNull { (domain, _) ->
            host == domain || host.endsWith(".$domain")
        }?.value.orEmpty()
    }

    fun isOfficialDirect(url: String): Boolean {
        if (url == HEBEI_SATELLITE) return true
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return OFFICIAL_STREAM_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    /** Resolve short-lived official URLs immediately before playback. */
    fun resolveForPlayback(url: String): String = when (url) {
        HEBEI_SATELLITE -> signedHebeiSatelliteUrl()
        else -> url
    }

    private fun signedHebeiSatelliteUrl(): String {
        val expiresAt = System.currentTimeMillis() / 1000 + HEBEI_TOKEN_LIFETIME_SECONDS
        val signature = md5("$HEBEI_LIVE_URI$HEBEI_LIVE_KEY$expiresAt")
        return "https://tv.pull.hebtv.com$HEBEI_LIVE_URI?t=$expiresAt&k=$signature"
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun webUrl(url: String): String = WEB_PREFIX + url

    private fun normalize(value: String): String {
        val compact = value.trim().uppercase().replace("CCTV-", "CCTV").replace(" ", "")
        return when {
            compact == "CCTV4K" || compact == "CCTV8K" -> compact
            compact.startsWith("CCTV") -> Regex("^(CCTV(?:5\\+|\\d{1,2}))")
                .find(compact)?.groupValues?.get(1) ?: compact
            compact == "CGTN英语" || compact == "CGTN英语频道" -> "CGTN"
            else -> compact
        }
    }

    private val OFFICIAL_STREAM_DOMAINS = listOf(
        "cctv.com",
        "cntv.cn",
        "centv.cn",
        "cgtn.com",
        "mgtv.com",
        "qing.mgtv.com",
        "cztv.com",
        "cztvcloud.com",
        "jstv.com",
        "hbtv.com.cn",
        "iqilu.com",
        "gdtv.cn",
        "gxtv.cn",
        "gxntv.com",
        "hebtv.com",
        "hebrts.cn",
        "btime.com",
        "gzstv.com",
        "yntv.cn",
        "hnntv.cn",
        "fjtv.net",
        "nxtv.cn",
        "nxtv.com.cn",
        "btzx.com.cn",
        "qhbtv.com.cn",
        "sztv.com.cn",
        "hntv.tv",
        "jxntv.cn",
        "jxgdw.com",
        "ahtv.cn",
        "nmtv.cn",
        "xjtvs.com.cn",
        "sxtvs.com",
        "sxrtv.com",
        "gstv.com.cn",
        "vtibet.cn",
        "liangtv.cn",
        "hljtv.com",
        "xntv.tv",
        "btzx.com.cn",
        "bestv.cn",
        "hebyun.com.cn",
        "jlntv.cn",
        "sctv.com",
        "iyb983.cn",
        "sdetv.com.cn",
        "kangbatv.com",
        "cnr.cn",
        "yangshipin.cn",
    )

    private val SOURCE_NAMES_BY_DOMAIN = linkedMapOf(
        "yangshipin.cn" to "央视频",
        "cctv.com" to "央视网",
        "cntv.cn" to "央视网",
        "cgtn.com" to "CGTN",
        "centv.cn" to "中国教育电视台",
        "mgtv.com" to "芒果TV",
        "kankanews.com" to "看看新闻·上海广电",
        "hntv.tv" to "大象新闻·河南广电",
        "jstv.com" to "荔枝网·江苏广电",
        "hbtv.com.cn" to "长江云·湖北广电",
        "iqilu.com" to "齐鲁网·山东广电",
        "cztv.com" to "中国蓝TV·浙江广电",
        "cztvcloud.com" to "中国蓝TV·浙江广电",
        "gdtv.cn" to "广东广播电视台",
        "gxtv.cn" to "广西网络广播电视台",
        "gxntv.com" to "广西网络广播电视台",
        "hebtv.com" to "河北广播电视台",
        "hebrts.cn" to "河北广播电视台",
        "btime.com" to "北京时间·北京广电",
        "gzstv.com" to "贵州广播电视台",
        "yntv.cn" to "云南广播电视台",
        "hnntv.cn" to "海南广播电视总台",
        "fjtv.net" to "福建网络广播电视台",
        "nxtv.com.cn" to "宁夏广播电视台",
        "btzx.com.cn" to "兵团广播电视台",
        "nmtv.cn" to "内蒙古广播电视台",
        "ahtv.cn" to "安徽网络广播电视台",
        "sctv.com" to "四川广播电视台",
        "iyb983.cn" to "延边广播电视台",
        "sdetv.com.cn" to "山东教育电视台",
        "xntv.tv" to "青海广播电视台",
        "kangbatv.com" to "康巴卫视",
    )
}
