package com.lizongying.mytv

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.models.TVListViewModel
import com.lizongying.mytv.models.TVViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A touch-friendly, vertically draggable channel selector for tablets. */
class MainFragment : Fragment() {

    private var itemPosition = 0
    private lateinit var channelList: RecyclerView
    private lateinit var channelAdapter: LiveChannelAdapter

    var tvListViewModel = TVListViewModel()

    private var sourceRefreshStarted = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_live_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        channelList = view.findViewById(R.id.live_channel_list)
        channelAdapter = LiveChannelAdapter(::selectChannel)
        channelList.layoutManager = LinearLayoutManager(requireContext())
        channelList.adapter = channelAdapter

        view.setOnClickListener { (activity as? MainActivity)?.switchMainFragment() }

        loadChannels()
        observeChannelEvents()
        channelList.post { scrollToSelectedChannel(smooth = false) }

        (activity as? MainActivity)?.fragmentReady("MainFragment")
    }

    private fun loadChannels() {
        var groupIndex = 0
        for ((_, channels) in TVList.list) {
            channels.forEachIndexed { channelIndex, tv ->
                val tvViewModel = TVViewModel(tv)
                tvViewModel.setRowPosition(groupIndex)
                tvViewModel.setItemPosition(channelIndex)
                tvListViewModel.addTVViewModel(tvViewModel)
            }
            groupIndex++
        }

        itemPosition = SP.itemPosition.coerceIn(0, (tvListViewModel.size() - 1).coerceAtLeast(0))
        tvListViewModel.setItemPosition(itemPosition)
        channelAdapter.submit(tvListViewModel.tvListViewModel.value.orEmpty(), itemPosition)
    }

    private fun observeChannelEvents() {
        tvListViewModel.tvListViewModel.value?.forEach { tvViewModel ->
            tvViewModel.errInfo.observe(viewLifecycleOwner) { message ->
                if (message != null && tvViewModel.getTV().id == itemPosition) {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
            tvViewModel.ready.observe(viewLifecycleOwner) {
                if (tvViewModel.ready.value != null &&
                    tvViewModel.getTV().id == itemPosition &&
                    check(tvViewModel)
                ) {
                    Log.i(TAG, "ready ${tvViewModel.getTV().title}")
                    (activity as? MainActivity)?.play(tvViewModel)
                }
            }
            tvViewModel.change.observe(viewLifecycleOwner) {
                if (tvViewModel.change.value != null && tvViewModel.getTV().id == itemPosition) {
                    onChannelChanged(tvViewModel)
                }
            }
        }
    }

    private fun onChannelChanged(tvViewModel: TVViewModel) {
        val title = tvViewModel.getTV().title
        Log.i(TAG, "switch $title")
        channelAdapter.setSelectedChannel(itemPosition)
        scrollToSelectedChannel(smooth = true)

        if (tvViewModel.getTV().pid.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                Request.fetchData(tvViewModel)
            }
            (activity as? MainActivity)?.showInfoFragment(tvViewModel)
        } else if (check(tvViewModel)) {
            (activity as? MainActivity)?.play(tvViewModel)
            (activity as? MainActivity)?.showInfoFragment(tvViewModel)
        }
    }

    private fun selectChannel(tvViewModel: TVViewModel) {
        val selectedPosition = tvViewModel.getTV().id
        if (selectedPosition != itemPosition) {
            itemPosition = selectedPosition
            tvListViewModel.setItemPosition(itemPosition)
            channelAdapter.setSelectedChannel(itemPosition)
            startSelectedChannel()
        }
        (activity as? MainActivity)?.switchMainFragment()
    }

    private fun scrollToSelectedChannel(smooth: Boolean) {
        if (!::channelList.isInitialized || itemPosition !in 0 until channelAdapter.itemCount) {
            return
        }
        if (smooth) {
            channelList.smoothScrollToPosition(itemPosition)
        } else {
            channelList.scrollToPosition(itemPosition)
        }
    }

    /** Keep the active station visible whenever the draggable channel panel is opened. */
    fun revealCurrentChannel() {
        if (!::channelList.isInitialized || !::channelAdapter.isInitialized) return
        channelAdapter.setSelectedChannel(itemPosition)
        channelList.post {
            val layoutManager = channelList.layoutManager as? LinearLayoutManager ?: return@post
            val rowHeight = (72 * resources.displayMetrics.density).toInt()
            val offset = ((channelList.height - rowHeight) / 2).coerceAtLeast(0)
            layoutManager.scrollToPositionWithOffset(itemPosition, offset)
        }
    }

    fun check(tvViewModel: TVViewModel): Boolean {
        val title = tvViewModel.getTV().title
        val urls = tvViewModel.videoUrl.value.orEmpty()
        val index = tvViewModel.videoIndex.value ?: -1
        val videoUrl = urls.getOrNull(index)
        if (videoUrl.isNullOrEmpty()) {
            Log.w(TAG, "$title source is not ready: index=$index size=${urls.size}")
            return false
        }

        return true
    }

    fun fragmentReady() {
        lifecycleScope.launch {
            try {
                val refresh = IptvSource.init(requireContext())
                val stats = populateIptvUrls()
                Log.i(
                    TAG,
                    "IPTV sources ready: ${refresh.channelCount} streams from " +
                        "${refresh.providerCount} indexes; ${stats.matched} local channels matched, " +
                        "${stats.official} have official sources, ${stats.unmatched} unmatched",
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load IPTV sources: ${e.message}")
            }
            tvListViewModel.getTVViewModel(itemPosition)?.changed()

            Request.fetchPage {
                view?.post {
                    val refreshedStats = populateIptvUrls()
                    Log.i(
                        TAG,
                        "Yangshipin channel map applied: official=${refreshedStats.official}, " +
                            "matched=${refreshedStats.matched}",
                    )
                    tvListViewModel.getTVViewModel(itemPosition)?.changed()
                }
            }
        }

        startPeriodicSourceRefresh()

        tvListViewModel.tvListViewModel.value?.forEach(::updateEPG)
    }

    private fun populateIptvUrls(): SourceMatchStats {
        var matched = 0
        var official = 0
        var unmatched = 0
        tvListViewModel.tvListViewModel.value?.forEach { tvViewModel ->
            if (tvViewModel.getTV().programType == ProgramType.IPTV) {
                val alias = tvViewModel.getTV().alias
                val name = tvViewModel.getTV().title
                val curatedOfficialUrls = OfficialLiveSources.sourcesFor(alias, name)
                val fallbackSources = IptvSource.getUrls(alias, name)
                val fallbackUrls = fallbackSources.map { it.url }
                val yangshipinUrls = curatedOfficialUrls.filter { url ->
                    OfficialLiveSources.isWeb(url) &&
                        OfficialLiveSources.unwrapWeb(url).contains("yangshipin.cn/")
                }
                // Only explicitly curated station streams may be treated as official direct
                // routes.  Public indexes occasionally mislabel another station's CDN URL.
                val officialDirectUrls = curatedOfficialUrls
                    .filter { !OfficialLiveSources.isWeb(it) && OfficialLiveSources.isOfficialDirect(it) }
                val officialWebUrls = curatedOfficialUrls
                    .filter(OfficialLiveSources::isWeb)
                    .filterNot(yangshipinUrls::contains)
                val otherUrls = fallbackUrls.filterNot { url ->
                    // A CDN owned by broadcaster A is not a valid fallback for broadcaster B.
                    // Keep official-domain direct streams only when curated for this channel.
                    OfficialLiveSources.isOfficialDirect(url) && url !in curatedOfficialUrls
                }
                val adaptiveWebUrls = orderOfficialWebSources(
                    name,
                    yangshipinUrls,
                    officialWebUrls,
                )
                val orderedWebUrls = if (alias in FORCE_YANGSHIPIN_CHANNELS) {
                    (yangshipinUrls + officialWebUrls).distinct()
                } else {
                    adaptiveWebUrls
                }
                val unorderedUrls = (
                    officialDirectUrls + orderedWebUrls + otherUrls
                    ).distinct()
                val stableSource = SP.stableSource(name)
                val urls = if (stableSource in unorderedUrls) {
                    listOf(stableSource) + unorderedUrls.filterNot { it == stableSource }
                } else {
                    unorderedUrls
                }
                val sourceNames = buildMap {
                    curatedOfficialUrls.forEach { url ->
                        put(url, OfficialLiveSources.sourceName(url).ifBlank { "频道官网" })
                    }
                    fallbackSources.forEach { source ->
                        putIfAbsent(
                            source.url,
                            OfficialLiveSources.sourceName(source.url)
                                .ifBlank { source.provider.ifBlank { "公共 IPTV" } },
                        )
                    }
                }
                val officialCount = (
                    yangshipinUrls + officialDirectUrls + officialWebUrls
                    ).distinct().size
                val preferredSource = when {
                    stableSource in urls -> stableSource
                    alias in FORCE_YANGSHIPIN_CHANNELS && yangshipinUrls.isNotEmpty() ->
                        yangshipinUrls.first()
                    else -> SP.preferredSource(name)
                }
                tvViewModel.replaceVideoUrls(urls, preferredSource, sourceNames)
                if (urls.isNotEmpty()) {
                    matched++
                    if (officialCount > 0) {
                        official++
                    }
                    Log.i(
                        TAG,
                        "SOURCE_REPORT $name | official=$officialCount | " +
                            "fallback=${fallbackUrls.size} | total=${urls.size} | " +
                            "names=${urls.mapNotNull(sourceNames::get)
                                .distinct().joinToString(",")}",
                    )
                } else {
                    unmatched++
                    Log.w(TAG, "IPTV no match: $name (alias=$alias)")
                }
            }
        }
        channelAdapter.notifyDataSetChanged()
        return SourceMatchStats(matched, official, unmatched)
    }

    /**
     * Yangshipin is the safe default.  Once both web routes have real device measurements, the
     * broadcaster site wins when it starts no more than 1.5 seconds later because it commonly
     * exposes the higher-quality rendition.  Recent failures add a strong ranking penalty.
     */
    private fun orderOfficialWebSources(
        channelName: String,
        yangshipinUrls: List<String>,
        broadcasterUrls: List<String>,
    ): List<String> {
        if (yangshipinUrls.isEmpty() || broadcasterUrls.isEmpty()) {
            return yangshipinUrls + broadcasterUrls
        }
        val yangshipin = yangshipinUrls.first()
        val broadcaster = broadcasterUrls.first()
        val yangshipinStartup = SP.sourceStartupMs(channelName, yangshipin)
        val broadcasterStartup = SP.sourceStartupMs(channelName, broadcaster)
        val yangshipinFailures = SP.sourceFailureCount(channelName, yangshipin)
        val broadcasterFailures = SP.sourceFailureCount(channelName, broadcaster)
        val broadcasterFirst = when {
            broadcasterFailures < yangshipinFailures && broadcasterStartup >= 0L -> true
            broadcasterFailures > yangshipinFailures -> false
            yangshipinStartup >= 0L && broadcasterStartup >= 0L ->
                broadcasterStartup <= yangshipinStartup + BROADCASTER_QUALITY_BIAS_MS
            else -> false
        }
        val primaryPair = if (broadcasterFirst) {
            listOf(broadcaster, yangshipin)
        } else {
            listOf(yangshipin, broadcaster)
        }
        Log.i(
            TAG,
            "ADAPTIVE_SOURCE $channelName primary=${OfficialLiveSources.sourceName(primaryPair[0])} " +
                "ysp=${yangshipinStartup}ms/$yangshipinFailures " +
                "official=${broadcasterStartup}ms/$broadcasterFailures",
        )
        return (primaryPair + yangshipinUrls.drop(1) + broadcasterUrls.drop(1)).distinct()
    }

    /** Refresh while the TV app stays open; the persisted cache is also checked on every start. */
    private fun startPeriodicSourceRefresh() {
        if (sourceRefreshStarted) return
        sourceRefreshStarted = true
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(IptvSource.REFRESH_INTERVAL_MS)
                try {
                    val result = IptvSource.refresh(requireContext(), force = true)
                    val stats = populateIptvUrls()
                    Log.i(
                        TAG,
                        "Periodic IPTV refresh: refreshed=${result.refreshed}, " +
                            "streams=${result.channelCount}, matched=${stats.matched}",
                    )
                    tvListViewModel.getTVViewModel(itemPosition)?.changed()
                } catch (e: Exception) {
                    Log.w(TAG, "Periodic IPTV refresh failed: ${e.message}")
                }
            }
        }
    }

    fun nextSource() {
        val current = tvListViewModel.getTVViewModel(itemPosition) ?: return
        if (!current.cycleSource()) {
            Toast.makeText(context, "当前频道没有其他线路", Toast.LENGTH_SHORT).show()
        } else {
            SP.setPreferredSource(current.getTV().title, current.getVideoUrlCurrent())
            Toast.makeText(context, "已切换，并设为该频道默认线路", Toast.LENGTH_SHORT).show()
        }
    }

    fun selectSource(index: Int) {
        val current = tvListViewModel.getTVViewModel(itemPosition) ?: return
        if (!current.selectSource(index)) return
        SP.setPreferredSource(current.getTV().title, current.getVideoUrlCurrent())
        Toast.makeText(
            context,
            "已选择 ${current.getVideoSourceNameCurrent()}，并设为默认线路",
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun markStableSource(index: Int) {
        val current = tvListViewModel.getTVViewModel(itemPosition) ?: return
        val source = current.videoUrl.value.orEmpty().getOrNull(index) ?: return
        SP.setStableSource(current.getTV().title, source)
        Toast.makeText(
            context,
            "已将 ${current.getVideoSourceName(source)} 标记为稳定默认",
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun clearStableSource() {
        val current = tvListViewModel.getTVViewModel(itemPosition) ?: return
        SP.clearStableSource(current.getTV().title)
        Toast.makeText(context, "已取消稳定线路标记", Toast.LENGTH_SHORT).show()
    }

    fun play(itemPosition: Int) {
        view?.post {
            if (itemPosition in 0 until tvListViewModel.size()) {
                this.itemPosition = itemPosition
                tvListViewModel.setItemPosition(itemPosition)
                channelAdapter.setSelectedChannel(itemPosition)
                startSelectedChannel()
            } else {
                Toast.makeText(context, "频道不存在", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun prev() = changeChannelBy(-1)

    fun next() = changeChannelBy(1)

    private fun changeChannelBy(offset: Int) {
        view?.post {
            val size = tvListViewModel.size()
            if (size == 0) return@post
            itemPosition = (itemPosition + offset + size) % size
            tvListViewModel.setItemPosition(itemPosition)
            channelAdapter.setSelectedChannel(itemPosition)
            startSelectedChannel()
        }
    }

    private fun startSelectedChannel() {
        val model = tvListViewModel.getTVViewModel(itemPosition) ?: return
        val title = model.getTV().title
        val rememberedSource = SP.stableSource(title).ifBlank { SP.preferredSource(title) }
        if (rememberedSource.isNotBlank()) {
            model.prepareSource(rememberedSource)
        } else {
            // Automatic fallback is local to the current viewing session. Returning to a channel
            // retries its primary official route because copyright blocks and outages are often
            // limited to one programme or one time window.
            model.videoUrl.value.orEmpty().firstOrNull()?.let(model::prepareSource)
        }
        model.changed()
    }

    private fun updateEPG(tvViewModel: TVViewModel) {
        when (tvViewModel.getTV().programType) {
            ProgramType.Y_PROTO -> Request.fetchYProtoEPG(tvViewModel)
            ProgramType.Y_JCE -> Request.fetchYJceEPG(tvViewModel)
            ProgramType.F -> Request.fetchFEPG(tvViewModel)
            ProgramType.IPTV -> Unit
        }
    }

    override fun onStop() {
        super.onStop()
        SP.itemPosition = itemPosition
    }

    companion object {
        private const val TAG = "MainFragment"
        private const val BROADCASTER_QUALITY_BIAS_MS = 1_500L
        private val FORCE_YANGSHIPIN_CHANNELS = setOf("CCTV13", "浙江卫视")
    }

    private data class SourceMatchStats(
        val matched: Int,
        val official: Int,
        val unmatched: Int,
    )
}
