package com.lizongying.mytv.models

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lizongying.mytv.TV
import com.lizongying.mytv.api.FEPG
import com.lizongying.mytv.proto.Ysp.cn.yangshipin.omstv.common.proto.programModel.Program
import com.tencent.videolite.android.datamodel.cctvjce.TVProgram
import java.text.SimpleDateFormat
import java.util.TimeZone

class TVViewModel(private var tv: TV) : ViewModel() {

    private var rowPosition: Int = 0
    private var itemPosition: Int = 0

    var retryTimes = 0
    var retryMaxTimes = 8
    var tokenYSPRetryTimes = 0
    var tokenYSPRetryMaxTimes = 0
    var tokenFHRetryTimes = 0
    var tokenFHRetryMaxTimes = 8

    var needGetToken = false
    private var consecutiveSourceFailures = 0
    private var videoSourceNames: Map<String, String> = emptyMap()

    private val _errInfo = MutableLiveData<String>()
    val errInfo: LiveData<String>
        get() = _errInfo

    private var _epg = MutableLiveData<MutableList<EPG>>()
    val epg: LiveData<MutableList<EPG>>
        get() = _epg

    private val _videoUrl = MutableLiveData<List<String>>()
    val videoUrl: LiveData<List<String>>
        get() = _videoUrl

    private val _videoIndex = MutableLiveData<Int>()
    val videoIndex: LiveData<Int>
        get() = _videoIndex

    private val _change = MutableLiveData<Boolean>()
    val change: LiveData<Boolean>
        get() = _change

    private val _ready = MutableLiveData<Boolean>()
    val ready: LiveData<Boolean>
        get() = _ready

    var seq = 0

    fun addVideoUrl(url: String) {
        if (url.isBlank() || tv.videoUrl.contains(url)) return
        if (_videoUrl.value?.isNotEmpty() == true) {
            if (_videoUrl.value!!.last().contains("cctv.cn")) {
                tv.videoUrl = tv.videoUrl.subList(0, tv.videoUrl.lastIndex) + listOf(url)
            } else {
                tv.videoUrl = tv.videoUrl + listOf(url)
            }
        } else {
            tv.videoUrl = tv.videoUrl + listOf(url)
        }
        _videoUrl.value = tv.videoUrl
        _videoIndex.value = tv.videoUrl.lastIndex
    }

    /** Replace IPTV sources while restoring the last source deliberately selected by the user. */
    fun replaceVideoUrls(
        urls: List<String>,
        preferredUrl: String = "",
        sourceNames: Map<String, String> = emptyMap(),
    ) {
        tv.videoUrl = urls.filter { it.isNotBlank() }.distinct()
        videoSourceNames = sourceNames.filterKeys(tv.videoUrl::contains)
        _videoUrl.value = tv.videoUrl
        val preferredIndex = tv.videoUrl.indexOf(preferredUrl)
        _videoIndex.value = when {
            tv.videoUrl.isEmpty() -> -1
            preferredIndex >= 0 -> preferredIndex
            else -> 0
        }
        consecutiveSourceFailures = 0
    }

    /** Move to another source automatically, stopping after every source failed once. */
    fun advanceSourceAfterError(): Boolean {
        val urls = _videoUrl.value.orEmpty()
        val current = _videoIndex.value ?: -1
        if (urls.size <= 1 || current !in urls.indices || consecutiveSourceFailures >= urls.lastIndex) {
            return false
        }
        consecutiveSourceFailures++
        _videoIndex.value = (current + 1) % urls.size
        Log.w(TAG, "${tv.title}: source failed, switching to ${_videoIndex.value}")
        return true
    }

    /** Manual source switching remains available even after an automatic failure round. */
    fun cycleSource(): Boolean {
        val urls = _videoUrl.value.orEmpty()
        val current = _videoIndex.value ?: -1
        if (urls.size <= 1 || current !in urls.indices) return false
        _videoIndex.value = (current + 1) % urls.size
        consecutiveSourceFailures = 0
        changed()
        return true
    }

    fun selectSource(index: Int): Boolean {
        val urls = _videoUrl.value.orEmpty()
        if (index !in urls.indices) return false
        _videoIndex.value = index
        consecutiveSourceFailures = 0
        changed()
        return true
    }

    /** Select a remembered route without starting playback until the channel change is emitted. */
    fun prepareSource(source: String): Boolean {
        val index = _videoUrl.value.orEmpty().indexOf(source)
        if (index < 0) return false
        _videoIndex.value = index
        consecutiveSourceFailures = 0
        return true
    }

    fun markSourcePlaying() {
        consecutiveSourceFailures = 0
    }

    fun firstSource() {
        if (_videoUrl.value!!.isNotEmpty()) {
            setVideoIndex(0)
            allReady()
        } else {
            Log.e(TAG, "no first")
        }
    }

    fun changed() {
        _change.value = true
    }

    fun allReady() {
        _ready.value = true
    }

    fun setVideoIndex(videoIndex: Int) {
        _videoIndex.value = videoIndex
    }

    init {
        _videoUrl.value = tv.videoUrl
        _videoIndex.value = tv.videoUrl.lastIndex
    }

    fun getRowPosition(): Int {
        return rowPosition
    }

    fun getItemPosition(): Int {
        return itemPosition
    }

    fun setRowPosition(position: Int) {
        rowPosition = position
    }

    fun setItemPosition(position: Int) {
        itemPosition = position
    }

    fun setErrInfo(info: String) {
        _errInfo.value = info
    }

    fun getTV(): TV {
        return tv
    }

    fun addYJceEPG(p: MutableList<TVProgram>) {
        _epg.value = p.map { EPG(it.name, it.start_time_stamp.toInt()) }.toMutableList()
    }

    fun addYEPG(p: MutableList<Program>) {
        _epg.value = p.map { EPG(it.name, it.st.toInt()) }.toMutableList()
    }

    private fun formatFTime(s: String): Int {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val date = dateFormat.parse(s.substring(0, 19))
        if (date != null) {
            return (date.time / 1000).toInt()
        }
        return 0
    }

    fun addFEPG(p: List<FEPG>) {
        _epg.value = p.map { EPG(it.title, formatFTime(it.event_time)) }.toMutableList()
    }

    fun getVideoUrlCurrent(): String {
        val urls = _videoUrl.value.orEmpty()
        val index = _videoIndex.value ?: -1
        return urls.getOrNull(index).orEmpty()
    }

    fun getVideoSourceNameCurrent(): String = videoSourceNames[getVideoUrlCurrent()].orEmpty()

    fun getVideoSourceName(url: String): String = videoSourceNames[url].orEmpty()

    companion object {
        private const val TAG = "TVViewModel"
    }
}
