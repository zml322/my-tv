package com.lizongying.mytv

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.exoplayer2.SimpleExoPlayer
import com.lizongying.mytv.databinding.PlayerBinding
import com.lizongying.mytv.models.TVViewModel


class PlayerFragment : Fragment(), SurfaceHolder.Callback {

    private var _binding: PlayerBinding? = null
    private var playerView: PlayerView? = null
    private var officialWebView: WebView? = null
    private var officialLoadingOverlay: View? = null
    private var officialLoadingProgress: View? = null
    private var officialLoadingText: TextView? = null
    private var tvViewModel: TVViewModel? = null
    private val aspectRatio = 16f / 9f

    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private var exoPlayer: SimpleExoPlayer? = null

    // Touch overlay
    private var touchOverlay: View? = null
    private var channelNameText: TextView? = null
    private var sourceLabelText: TextView? = null
    private var controlsVisible = true
    private lateinit var playerSurfaceGestureDetector: GestureDetector
    private lateinit var controlsGestureDetector: GestureDetector
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private val playbackTimeoutHandler = Handler(Looper.getMainLooper())
    private val playbackHealthHandler = Handler(Looper.getMainLooper())
    private val officialProbeHandler = Handler(Looper.getMainLooper())
    private var playbackAttempt = 0
    private var nativeHealthGeneration = 0
    private var nativeLastPositionMs = 0L
    private var nativeStallCount = 0
    private var officialProbeGeneration = 0
    private var officialProbeCount = 0
    private var officialHealthFailureCount = 0
    private var officialMainFrameUrl: String? = null
    private var sourceStartedAtMs = 0L
    private var sourceStartedUrl = ""
    private var nativePlaybackUri = ""
    private val autoHideDelayMs = 5000L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlayerBinding.inflate(inflater, container, false)

        if (Utils.isTmallDevice()) {
            _binding!!.playerView.visibility = View.GONE
            surfaceView = _binding!!.surfaceView
            surfaceHolder = surfaceView.holder
            surfaceHolder.addCallback(this)
        } else {
            _binding!!.surfaceView.visibility = View.GONE
            playerView = _binding!!.playerView
        }

        // Setup touch overlay
        touchOverlay = _binding!!.touchOverlay
        channelNameText = _binding!!.channelName
        sourceLabelText = _binding!!.sourceLabel
        officialWebView = _binding!!.officialWebView
        officialLoadingOverlay = _binding!!.officialLoadingOverlay
        officialLoadingProgress = _binding!!.officialLoadingProgress
        officialLoadingText = _binding!!.officialLoadingText
        configureOfficialWebView()
        playerSurfaceGestureDetector = GestureDetector(requireContext(), PlayerSurfaceGestureListener())
        controlsGestureDetector = GestureDetector(requireContext(), ControlsGestureListener())
        setupTouchControls()
        configurePlayerSurfaceTouch(playerView ?: surfaceView)
        configurePlayerSurfaceTouch(_binding!!.officialWebView, consumeTouch = false)

        playerView?.let { vp ->
            vp.post {
                vp.player = activity?.let {
                    ExoPlayer.Builder(it).build()
                }
                vp.player?.playWhenReady = true
                vp.player?.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (vp.measuredWidth == 0 || vp.measuredHeight == 0) {
                            return
                        }

                        val ratio = vp.measuredWidth.toFloat() / vp.measuredHeight.toFloat()
                        val layoutParams = vp.layoutParams
                        if (ratio < aspectRatio) {
                            layoutParams.height = (vp.measuredWidth / aspectRatio).toInt()
                            vp.layoutParams = layoutParams
                        } else if (ratio > aspectRatio) {
                            layoutParams.width = (vp.measuredHeight * aspectRatio).toInt()
                            vp.layoutParams = layoutParams
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "PlaybackException $error")
                        handleSourceFailure("播放器错误 ${error.errorCodeName}")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            val actualUri = vp.player?.currentMediaItem?.localConfiguration
                                ?.uri?.toString().orEmpty()
                            if (actualUri != nativePlaybackUri ||
                                OfficialLiveSources.isWeb(tvViewModel?.getVideoUrlCurrent().orEmpty())
                            ) {
                                Log.w(
                                    TAG,
                                    "Ignoring stale playback callback actual=$actualUri " +
                                        "expected=$nativePlaybackUri",
                                )
                                return
                            }
                            playbackTimeoutHandler.removeCallbacksAndMessages(null)
                            recordSourceStartup()
                            tvViewModel?.markSourcePlaying()
                            tvViewModel?.getVideoUrlCurrent()?.let(::startNativePlaybackHealthMonitor)
                            Log.i(
                                TAG,
                                "PLAYBACK_SUCCESS ${tvViewModel?.getTV()?.title}: " +
                                    "${sourceLabelText?.text}",
                            )
                            (activity as? MainActivity)?.isPlaying()
                        }
                    }
                })
            }
        }
        (activity as? MainActivity)?.fragmentReady("PlayerFragment")
        return _binding!!.root
    }

    private fun setupTouchControls() {
        val btnPrev = touchOverlay?.findViewById<View>(R.id.btn_prev)
        val btnNext = touchOverlay?.findViewById<View>(R.id.btn_next)
        val btnSource = touchOverlay?.findViewById<View>(R.id.btn_source)
        val centerArea = touchOverlay?.findViewById<View>(R.id.center_tap_area)
        // The full-screen centre target is declared after the top bar in XML.  Keep the visible
        // controls in front so a tap on "切换线路" cannot fall through and open the channel list.
        touchOverlay?.findViewById<View>(R.id.channel_info_bar)?.bringToFront()

        btnPrev?.setOnClickListener {
            (activity as? MainActivity)?.prev()
            showControlsTemporarily()
        }

        btnNext?.setOnClickListener {
            (activity as? MainActivity)?.next()
            showControlsTemporarily()
        }

        btnSource?.setOnClickListener {
            showSourcePicker()
            showControlsTemporarily()
        }

        centerArea?.setOnClickListener {
            // The channel list is only opened deliberately from the centre area.
            (activity as? MainActivity)?.switchMainFragment()
        }
        centerArea?.setOnTouchListener { _, event ->
            controlsGestureDetector.onTouchEvent(event)
            false
        }

        // Tapping empty space in the visible control layer dismisses it. Child controls consume
        // their own taps, so channel changes and Settings never trigger an extra global action.
        touchOverlay?.setOnClickListener {
            hideControls()
        }

        showControlsTemporarily()
    }

    private fun configurePlayerSurfaceTouch(surface: View, consumeTouch: Boolean = true) {
        surface.isClickable = true
        surface.setOnTouchListener { _, event ->
            playerSurfaceGestureDetector.onTouchEvent(event)
            consumeTouch
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureOfficialWebView() {
        val webView = officialWebView ?: return
        webView.visibility = View.GONE
        webView.alpha = 0f
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = DESKTOP_USER_AGENT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                officialMainFrameUrl = url
                Log.i(TAG, "OFFICIAL_WEB_LOADING ${tvViewModel?.getTV()?.title}: $url")
                if (url != "about:blank" && isOfficialWebActive()) {
                    coverOfficialPage("正在载入${currentSourceOwnerLabel()}直播…")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == "about:blank" || !isOfficialWebActive()) return
                if (url != officialMainFrameUrl) {
                    Log.i(TAG, "OFFICIAL_WEB_IGNORED stale=$url current=$officialMainFrameUrl")
                    return
                }
                Log.i(TAG, "OFFICIAL_WEB_READY ${tvViewModel?.getTV()?.title}: $url")
                val source = tvViewModel?.getVideoUrlCurrent().orEmpty()
                sourceLabelText?.text = currentSourceLabel(source) + " · 正在准备"
                view?.let { webView ->
                    startOfficialPlaybackProbe(webView, source)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val scheme = request?.url?.scheme
                return scheme != "http" && scheme != "https"
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    if (isOfficialWebActive()) {
                        handleSourceFailure("官网加载失败 ${error?.errorCode}")
                    }
                }
            }
        }
    }

    private fun isOfficialWebActive(): Boolean =
        tvViewModel?.getVideoUrlCurrent()?.let(OfficialLiveSources::isWeb) == true

    private fun coverOfficialPage(message: String, showProgress: Boolean = true) {
        officialWebView?.alpha = 0f
        officialLoadingText?.text = message
        officialLoadingProgress?.visibility = if (showProgress) View.VISIBLE else View.GONE
        officialLoadingOverlay?.visibility = View.VISIBLE
    }

    private fun cancelOfficialPlaybackProbe() {
        officialProbeGeneration++
        officialProbeHandler.removeCallbacksAndMessages(null)
    }

    private fun startOfficialPlaybackProbe(view: WebView, source: String) {
        if (!OfficialLiveSources.isWeb(source)) return
        cancelOfficialPlaybackProbe()
        officialProbeCount = 0
        val generation = officialProbeGeneration
        officialProbeHandler.postDelayed(
            { probeOfficialPlayback(view, source, generation) },
            OFFICIAL_FIRST_PROBE_DELAY_MS,
        )
    }

    private fun probeOfficialPlayback(view: WebView, source: String, generation: Int) {
        if (generation != officialProbeGeneration ||
            tvViewModel?.getVideoUrlCurrent() != source ||
            !OfficialLiveSources.isWeb(source)
        ) {
            return
        }

        officialProbeCount++
        view.evaluateJavascript(PREPARE_AND_STATUS_SCRIPT) { result ->
            if (generation != officialProbeGeneration ||
                tvViewModel?.getVideoUrlCurrent() != source
            ) {
                return@evaluateJavascript
            }

            Log.i(TAG, "OFFICIAL_WEB_VIDEO ${tvViewModel?.getTV()?.title}: $result")
            when {
                result.contains("official-copyright") ->
                    handleSourceFailure(
                        "当前节目版权受限",
                        recordFailure = false,
                        temporary = true,
                    )

                result.contains("official-blocked") ->
                    handleSourceFailure("官网当前需要登录或不允许播放")

                result.contains("paused=false") && result.contains("progressed=true") ->
                    revealOfficialVideo(view, source, result)

                result.contains("no-video") &&
                    officialProbeCount >= OFFICIAL_NO_VIDEO_PROBE_COUNT ->
                    handleSourceFailure(
                        if (OfficialLiveSources.requiresLogin(source)) "官网需要登录"
                        else "官网未检测到可播放视频",
                    )

                officialProbeCount < OFFICIAL_MAX_PROBE_COUNT ->
                    officialProbeHandler.postDelayed(
                        { probeOfficialPlayback(view, source, generation) },
                        OFFICIAL_PROBE_INTERVAL_MS,
                    )

                result.contains("ready=0") ->
                    handleSourceFailure("官网视频未加载出媒体数据")

                result.contains("paused=true") ->
                    handleSourceFailure("官网播放按钮未能启动视频")

                else -> handleSourceFailure("官网视频没有开始播放")
            }
        }
    }

    private fun revealOfficialVideo(view: WebView, source: String, status: String) {
        cancelOfficialPlaybackProbe()
        view.evaluateJavascript(FULLSCREEN_VIDEO_SCRIPT) { result ->
            if (tvViewModel?.getVideoUrlCurrent() != source) return@evaluateJavascript
            if (!result.contains("fullscreen")) {
                handleSourceFailure("官网视频无法切换为全屏")
                return@evaluateJavascript
            }

            val soundConfirmed = status.contains("audible=true")
            officialLoadingOverlay?.visibility = View.GONE
            view.alpha = 1f
            sourceLabelText?.text = currentSourceLabel(source) +
                if (soundConfirmed) " · 有声" else " · 播放中"
            recordSourceStartup(source)
            tvViewModel?.markSourcePlaying()
            Log.i(
                TAG,
                "OFFICIAL_WEB_PLAYBACK ${tvViewModel?.getTV()?.title}: " +
                    "fullscreen=true sound=$soundConfirmed",
            )
            (activity as? MainActivity)?.isPlaying()
            startOfficialPlaybackHealthMonitor(view, source)
        }
    }

    private fun startOfficialPlaybackHealthMonitor(view: WebView, source: String) {
        if (!OfficialLiveSources.isWeb(source)) return
        cancelOfficialPlaybackProbe()
        officialHealthFailureCount = 0
        val generation = officialProbeGeneration
        officialProbeHandler.postDelayed(
            { probeOfficialPlaybackHealth(view, source, generation) },
            PLAYBACK_HEALTH_INTERVAL_MS,
        )
    }

    private fun probeOfficialPlaybackHealth(view: WebView, source: String, generation: Int) {
        if (generation != officialProbeGeneration ||
            tvViewModel?.getVideoUrlCurrent() != source ||
            view.alpha == 0f
        ) {
            return
        }

        view.evaluateJavascript(OFFICIAL_HEALTH_SCRIPT) { result ->
            if (generation != officialProbeGeneration ||
                tvViewModel?.getVideoUrlCurrent() != source
            ) {
                return@evaluateJavascript
            }

            val healthy = result.contains("paused=false") && result.contains("progressed=true")
            if (healthy) {
                officialHealthFailureCount = 0
                officialProbeHandler.postDelayed(
                    { probeOfficialPlaybackHealth(view, source, generation) },
                    PLAYBACK_HEALTH_INTERVAL_MS,
                )
                return@evaluateJavascript
            }

            officialHealthFailureCount++
            Log.w(
                TAG,
                "OFFICIAL_WEB_STALL ${tvViewModel?.getTV()?.title}: " +
                    "$officialHealthFailureCount/$PLAYBACK_STALL_CHECKS $result",
            )
            if (officialHealthFailureCount >= PLAYBACK_STALL_CHECKS) {
                handleSourceFailure("官网直播播放卡住")
            } else {
                view.evaluateJavascript(PREPARE_AND_STATUS_SCRIPT, null)
                officialProbeHandler.postDelayed(
                    { probeOfficialPlaybackHealth(view, source, generation) },
                    PLAYBACK_HEALTH_INTERVAL_MS,
                )
            }
        }
    }

    private fun showControlsTemporarily() {
        touchOverlay?.visibility = View.VISIBLE
        controlsVisible = true
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, autoHideDelayMs)
    }

    private fun currentSourceLabel(source: String): String {
        val model = tvViewModel ?: return "直播来源"
        val urls = model.videoUrl.value.orEmpty()
        return OfficialLiveSources.sourceLabel(
            source,
            urls.indexOf(source).takeIf { it >= 0 } ?: (model.videoIndex.value ?: 0),
            urls.size,
            model.getVideoSourceName(source),
            model.getTV().title,
        )
    }

    private fun currentSourceOwnerLabel(): String {
        val source = tvViewModel?.getVideoUrlCurrent().orEmpty()
        return currentSourceLabel(source).substringBefore('｜').ifBlank { "官方" }
    }

    private fun hideControls() {
        touchOverlay?.visibility = View.GONE
        controlsVisible = false
    }

    private fun showSourcePicker() {
        val model = tvViewModel ?: return
        val urls = model.videoUrl.value.orEmpty()
        if (urls.isEmpty()) {
            Toast.makeText(requireContext(), "当前频道暂无线路", Toast.LENGTH_SHORT).show()
            return
        }
        val currentIndex = model.videoIndex.value ?: 0
        val stableSource = SP.stableSource(model.getTV().title)
        val labels = urls.mapIndexed { index, url ->
            val plainLabel = OfficialLiveSources.sourceLabel(
                url,
                index,
                urls.size,
                model.getVideoSourceName(url),
                model.getTV().title,
            )
            val sourceLabel = if (url == stableSource) "★ $plainLabel" else plainLabel
            val startupMs = SP.sourceStartupMs(model.getTV().title, url)
            val failures = SP.sourceFailureCount(model.getTV().title, url)
            when {
                startupMs >= 0L && failures > 0 ->
                    "$sourceLabel · 约 ${formatStartupSeconds(startupMs)} 秒 · 近期卡顿"
                startupMs >= 0L -> "$sourceLabel · 约 ${formatStartupSeconds(startupMs)} 秒"
                failures > 0 -> "$sourceLabel · 近期失败"
                else -> sourceLabel
            }
        }.toTypedArray()
        var selectedIndex = currentIndex
        val builder = AlertDialog.Builder(requireContext())
            .setTitle("${model.getTV().title} · 选择线路")
            .setSingleChoiceItems(labels, currentIndex) { _, index ->
                selectedIndex = index
                if (index != (model.videoIndex.value ?: -1)) {
                    (activity as? MainActivity)?.selectSource(index)
                }
            }
            .setPositiveButton("★ 标记为稳定默认") { _, _ ->
                (activity as? MainActivity)?.markStableSource(selectedIndex)
            }
            .setNegativeButton("关闭", null)
        if (stableSource.isNotBlank()) {
            builder.setNeutralButton("取消稳定标记") { _, _ ->
                (activity as? MainActivity)?.clearStableSource()
            }
        }
        builder.show()
    }

    /**
     * When the controls are hidden, a tap only reveals them.  It no longer changes the channel
     * list or opens settings through an Activity-wide gesture.
     */
    private inner class PlayerSurfaceGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            showControlsTemporarily()
            return true
        }
    }

    /**
     * Use one intentional vertical swipe to change channels while controls are visible.  Taps
     * are deliberately ignored here because the buttons and centre area already have clear jobs.
     */
    private inner class ControlsGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            val start = e1 ?: return false
            val deltaY = e2.y - start.y
            if (kotlin.math.abs(deltaY) < MIN_SWIPE_DISTANCE_PX ||
                kotlin.math.abs(deltaY) <= kotlin.math.abs(e2.x - start.x) ||
                kotlin.math.abs(velocityY) < MIN_SWIPE_VELOCITY_PX_PER_SECOND
            ) {
                return false
            }

            if (deltaY > 0) {
                (activity as? MainActivity)?.prev()
            } else {
                (activity as? MainActivity)?.next()
            }
            showControlsTemporarily()
            return true
        }
    }

    @OptIn(UnstableApi::class)
    fun play(tvViewModel: TVViewModel) {
        this.tvViewModel = tvViewModel
        cancelNativePlaybackHealthMonitor()
        // Update channel name display
        channelNameText?.text = tvViewModel.getTV().title
        val url = tvViewModel.getVideoUrlCurrent()
        sourceStartedAtMs = SystemClock.elapsedRealtime()
        sourceStartedUrl = url
        val index = tvViewModel.videoIndex.value ?: 0
        val total = tvViewModel.videoUrl.value.orEmpty().size
        sourceLabelText?.text = OfficialLiveSources.sourceLabel(
            url,
            index,
            total,
            tvViewModel.getVideoSourceNameCurrent(),
            tvViewModel.getTV().title,
        )
        showControlsTemporarily()

        if (OfficialLiveSources.isWeb(url)) {
            nativePlaybackUri = ""
            playbackTimeoutHandler.removeCallbacksAndMessages(null)
            playOfficialPage(OfficialLiveSources.unwrapWeb(url))
            return
        }

        cancelOfficialPlaybackProbe()
        officialLoadingOverlay?.visibility = View.GONE
        val playbackUrl = OfficialLiveSources.resolveForPlayback(url)
        nativePlaybackUri = playbackUrl

        officialWebView?.run {
            stopLoading()
            loadUrl("about:blank")
            alpha = 0f
            visibility = View.GONE
        }
        if (Utils.isTmallDevice()) {
            surfaceView.visibility = View.VISIBLE
        } else {
            playerView?.visibility = View.VISIBLE
        }

        playerView?.player?.run {
            setMediaItem(MediaItem.fromUri(playbackUrl))
            prepare()
            play()
        }
        exoPlayer?.run {
            setMediaItem(com.google.android.exoplayer2.MediaItem.fromUri(playbackUrl))
            prepare()
            play()
        }
        schedulePlaybackTimeout(url)
    }

    private fun schedulePlaybackTimeout(url: String) {
        val attempt = ++playbackAttempt
        playbackTimeoutHandler.removeCallbacksAndMessages(null)
        playbackTimeoutHandler.postDelayed({
            val currentUrl = tvViewModel?.getVideoUrlCurrent()
            val isPlaying = playerView?.player?.isPlaying == true || exoPlayer?.isPlaying == true
            if (attempt == playbackAttempt && currentUrl == url && !isPlaying) {
                handleSourceFailure("连接超时")
            }
        }, PLAYBACK_START_TIMEOUT_MS)
    }

    private fun cancelNativePlaybackHealthMonitor() {
        nativeHealthGeneration++
        playbackHealthHandler.removeCallbacksAndMessages(null)
    }

    private fun startNativePlaybackHealthMonitor(source: String) {
        if (source.isBlank() || OfficialLiveSources.isWeb(source)) return
        cancelNativePlaybackHealthMonitor()
        nativeLastPositionMs = currentNativePositionMs()
        nativeStallCount = 0
        val generation = nativeHealthGeneration
        playbackHealthHandler.postDelayed(
            { probeNativePlaybackHealth(source, generation) },
            PLAYBACK_HEALTH_INTERVAL_MS,
        )
    }

    private fun probeNativePlaybackHealth(source: String, generation: Int) {
        if (generation != nativeHealthGeneration ||
            tvViewModel?.getVideoUrlCurrent() != source ||
            OfficialLiveSources.isWeb(source)
        ) {
            return
        }

        val positionMs = currentNativePositionMs()
        val isPlaying = playerView?.player?.isPlaying == true || exoPlayer?.isPlaying == true
        val progressed = positionMs > nativeLastPositionMs + MIN_PLAYBACK_PROGRESS_MS
        nativeLastPositionMs = positionMs
        if (isPlaying && progressed) {
            nativeStallCount = 0
        } else {
            nativeStallCount++
            Log.w(
                TAG,
                "PLAYBACK_STALL ${tvViewModel?.getTV()?.title}: " +
                    "$nativeStallCount/$PLAYBACK_STALL_CHECKS position=$positionMs playing=$isPlaying",
            )
        }

        if (nativeStallCount >= PLAYBACK_STALL_CHECKS) {
            handleSourceFailure("直播播放卡住")
            return
        }
        if (nativeStallCount == 1) {
            playerView?.player?.play()
            exoPlayer?.play()
        }
        playbackHealthHandler.postDelayed(
            { probeNativePlaybackHealth(source, generation) },
            PLAYBACK_HEALTH_INTERVAL_MS,
        )
    }

    private fun currentNativePositionMs(): Long =
        playerView?.player?.currentPosition ?: exoPlayer?.currentPosition ?: 0L

    private fun playOfficialPage(url: String) {
        cancelNativePlaybackHealthMonitor()
        playerView?.player?.stop()
        exoPlayer?.stop()
        playerView?.visibility = View.GONE
        if (::surfaceView.isInitialized) surfaceView.visibility = View.GONE
        cancelOfficialPlaybackProbe()
        coverOfficialPage("正在准备${currentSourceOwnerLabel()}全屏直播…")
        officialWebView?.run {
            visibility = View.VISIBLE
            alpha = 0f
            settings.userAgentString = if (url.contains("m.mgtv.com")) {
                MOBILE_USER_AGENT
            } else {
                DESKTOP_USER_AGENT
            }
            stopLoading()
            officialMainFrameUrl = null
            loadUrl(url)
        }

        val source = tvViewModel?.getVideoUrlCurrent().orEmpty()
        val generation = officialProbeGeneration
        officialProbeHandler.postDelayed({
            if (generation == officialProbeGeneration &&
                tvViewModel?.getVideoUrlCurrent() == source &&
                officialWebView?.alpha == 0f
            ) {
                handleSourceFailure("官网页面加载超时")
            }
        }, OFFICIAL_PAGE_LOAD_TIMEOUT_MS)
    }

    private fun coverOfficialFailure(message: String) {
        cancelOfficialPlaybackProbe()
        officialWebView?.alpha = 0f
        coverOfficialPage(message, showProgress = false)
    }

    private fun prepareOfficialSourceTransition(message: String = "官网线路不可用，正在切换…") {
        cancelOfficialPlaybackProbe()
        officialWebView?.alpha = 0f
        if (isOfficialWebActive()) {
            coverOfficialPage(message)
        }
    }

    private fun handleSourceFailure(
        reason: String,
        recordFailure: Boolean = true,
        temporary: Boolean = false,
    ) {
        val model = tvViewModel ?: return
        val failedSource = model.getVideoUrlCurrent()
        if (recordFailure) {
            SP.recordSourceFailure(model.getTV().title, failedSource)
        }
        if (sourceStartedUrl == failedSource) {
            sourceStartedAtMs = 0L
            sourceStartedUrl = ""
        }
        playbackTimeoutHandler.removeCallbacksAndMessages(null)
        cancelNativePlaybackHealthMonitor()
        prepareOfficialSourceTransition(
            if (temporary) "$reason，正在尝试其他线路…"
            else "官网线路不可用，正在切换…",
        )
        if (model.advanceSourceAfterError()) {
            val next = model.videoIndex.value?.plus(1) ?: 0
            val total = model.videoUrl.value.orEmpty().size
            sourceLabelText?.text = if (temporary) {
                "$reason，正在切换 $next/$total"
            } else {
                "线路失败，切换中 $next/$total"
            }
            Log.w(TAG, "${model.getTV().title}: $reason; trying source $next/$total")
            model.changed()
        } else {
            sourceLabelText?.text = if (temporary) {
                "$reason，可稍后重试"
            } else {
                "全部线路暂不可用，可稍后重试"
            }
            if (OfficialLiveSources.isWeb(model.getVideoUrlCurrent())) {
                coverOfficialFailure(
                    if (temporary) "$reason，可稍后重试"
                    else "该频道当前暂无可播放线路",
                )
            }
            Log.e(TAG, "${model.getTV().title}: $reason; all sources exhausted")
        }
    }

    private fun recordSourceStartup(expectedSource: String? = null) {
        val model = tvViewModel ?: return
        val currentSource = model.getVideoUrlCurrent()
        if (expectedSource != null && currentSource != expectedSource) return
        if (sourceStartedAtMs <= 0L || sourceStartedUrl != currentSource) return
        val elapsedMs = SystemClock.elapsedRealtime() - sourceStartedAtMs
        SP.recordSourceStartup(model.getTV().title, currentSource, elapsedMs)
        Log.i(
            TAG,
            "SOURCE_STARTUP ${model.getTV().title}: " +
                "${model.getVideoSourceNameCurrent()} ${elapsedMs}ms",
        )
        sourceStartedAtMs = 0L
        sourceStartedUrl = ""
    }

    private fun formatStartupSeconds(startupMs: Long): String =
        String.format(java.util.Locale.CHINA, "%.1f", startupMs / 1_000.0)

    override fun onStart() {
        Log.i(TAG, "onStart")
        super.onStart()
        if (playerView != null && playerView!!.player?.isPlaying == false) {
            Log.i(TAG, "replay")
            playerView!!.player?.prepare()
            playerView!!.player?.play()
        }
        if (exoPlayer?.isPlaying == false) {
            Log.i(TAG, "replay")
            exoPlayer?.prepare()
            exoPlayer?.play()
        }
        val source = tvViewModel?.getVideoUrlCurrent().orEmpty()
        if (source.isNotBlank() &&
            !OfficialLiveSources.isWeb(source) &&
            (playerView?.player?.isPlaying == true || exoPlayer?.isPlaying == true)
        ) {
            startNativePlaybackHealthMonitor(source)
        }
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
        officialWebView?.onResume()
        val source = tvViewModel?.getVideoUrlCurrent().orEmpty()
        if (OfficialLiveSources.isWeb(source) && officialWebView?.alpha == 0f) {
            officialWebView?.let { startOfficialPlaybackProbe(it, source) }
        } else if (OfficialLiveSources.isWeb(source)) {
            officialWebView?.evaluateJavascript(PREPARE_AND_STATUS_SCRIPT, null)
            officialWebView?.let { startOfficialPlaybackHealthMonitor(it, source) }
        }
        showControlsTemporarily()
    }

    override fun onPause() {
        super.onPause()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        playbackTimeoutHandler.removeCallbacksAndMessages(null)
        cancelNativePlaybackHealthMonitor()
        cancelOfficialPlaybackProbe()
        officialWebView?.onPause()
        if (playerView != null && playerView!!.player?.isPlaying == true) {
            playerView!!.player?.stop()
        }
        if (exoPlayer?.isPlaying == true) {
            exoPlayer?.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        playbackTimeoutHandler.removeCallbacksAndMessages(null)
        cancelNativePlaybackHealthMonitor()
        cancelOfficialPlaybackProbe()
        if (playerView != null) {
            playerView!!.player?.release()
        }
        exoPlayer?.release()
    }

    override fun onDestroyView() {
        officialWebView?.run {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        officialWebView = null
        officialMainFrameUrl = null
        officialLoadingOverlay = null
        officialLoadingProgress = null
        officialLoadingText = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "PlayerFragment"
        private const val MIN_SWIPE_DISTANCE_PX = 96f
        private const val MIN_SWIPE_VELOCITY_PX_PER_SECOND = 600f
        private const val PLAYBACK_START_TIMEOUT_MS = 12_000L
        private const val PLAYBACK_HEALTH_INTERVAL_MS = 3_000L
        private const val PLAYBACK_STALL_CHECKS = 2
        private const val MIN_PLAYBACK_PROGRESS_MS = 250L
        private const val OFFICIAL_FIRST_PROBE_DELAY_MS = 350L
        private const val OFFICIAL_PROBE_INTERVAL_MS = 1_000L
        private const val OFFICIAL_NO_VIDEO_PROBE_COUNT = 6
        private const val OFFICIAL_MAX_PROBE_COUNT = 16
        private const val OFFICIAL_PAGE_LOAD_TIMEOUT_MS = 25_000L
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36 MyTVTablet/1.0"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Tablet) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 MyTVTablet/1.0"
        /**
         * Repeatedly presses a visible play control and calls HTMLMediaElement.play().  Playback
         * is accepted only after currentTime has advanced between probes.  Chromium's decoded
         * audio counter/audio tracks are used when available, but video progress remains the
         * fallback because some cross-origin HLS players do not expose audio-track metadata.
         */
        private const val PREPARE_AND_STATUS_SCRIPT =
            "javascript:(function(){var text=(document.body&&document.body.innerText)||'';" +
                "if(/版权限制|版权方要求|暂停提供直播信号/.test(text))return 'official-copyright';" +
                "if(/无法播放|登录后即可观看/.test(text))return 'official-blocked';" +
                "var els=document.querySelectorAll('img,div,video');" +
                "for(var i=0;i<els.length;i++){var e=els[i],s=getComputedStyle(e);" +
                "if(e.offsetParent!==null&&/video_poster\\d*\\.png/.test(" +
                "(e.src||'')+(e.poster||'')+(s.backgroundImage||'')))return 'official-copyright';}" +
                "var videos=Array.prototype.slice.call(document.querySelectorAll('video'));" +
                "videos.sort(function(a,b){return b.clientWidth*b.clientHeight-a.clientWidth*a.clientHeight;});" +
                "var v=videos[0]||null;" +
                "if(!v||v.paused){var nodes=document.querySelectorAll(" +
                "'button,[role=\\\"button\\\"],[class*=\\\"play\\\"],[id*=\\\"play\\\"]');" +
                "for(var j=0;j<nodes.length;j++){var n=nodes[j],r=n.getBoundingClientRect(),cs=getComputedStyle(n);" +
                "var label=((n.getAttribute('aria-label')||'')+' '+(n.title||'')+' '+" +
                "String(n.className||'')+' '+String(n.id||'')+' '+(n.innerText||''));" +
                "if(r.width>8&&r.height>8&&cs.display!=='none'&&cs.visibility!=='hidden'&&" +
                "/播放|立即观看|开始播放|big[-_ ]?play|play[-_ ]?(button|btn|icon)|" +
                "xgplayer-start|dplayer-play/i.test(label)&&!/暂停|pause/i.test(label)){n.click();break;}}}" +
                "if(!v)return 'no-video';" +
                "try{v.autoplay=true;v.playsInline=true;v.setAttribute('playsinline','');" +
                "v.setAttribute('webkit-playsinline','');v.muted=false;v.volume=1;" +
                "if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}}catch(ignore){}" +
                "var now=Number(v.currentTime||0),last=Number(v.dataset.mytvLastTime||'-1');" +
                "var progressed=last>=0&&now>last+0.05;v.dataset.mytvLastTime=String(now);" +
                "var decoded=Number(v.webkitAudioDecodedByteCount||v.mozAudioDecodedByteCount||0);" +
                "var hasAudio=decoded>0||!!(v.audioTracks&&v.audioTracks.length);" +
                "try{var stream=v.captureStream?v.captureStream():null;" +
                "hasAudio=hasAudio||!!(stream&&stream.getAudioTracks().length);}catch(ignore){}" +
                "var audible=hasAudio&&!v.muted&&v.volume>0;" +
                "return 'video ready='+v.readyState+' paused='+v.paused+" +
                "' progressed='+progressed+' audible='+audible+' decoded='+decoded;})()"

        private const val OFFICIAL_HEALTH_SCRIPT =
            "javascript:(function(){var videos=Array.prototype.slice.call(document.querySelectorAll('video'));" +
                "videos.sort(function(a,b){return b.clientWidth*b.clientHeight-a.clientWidth*a.clientHeight;});" +
                "var v=videos[0]||null;if(!v)return 'health no-video';" +
                "var now=Number(v.currentTime||0),last=Number(v.dataset.mytvHealthTime||'-1');" +
                "var progressed=last<0||now>last+0.05;v.dataset.mytvHealthTime=String(now);" +
                "return 'health ready='+v.readyState+' paused='+v.paused+' progressed='+progressed;})()"

        /** Hide every website element and promote only the playing video to the WebView viewport. */
        private const val FULLSCREEN_VIDEO_SCRIPT =
            "javascript:(function(){var videos=Array.prototype.slice.call(document.querySelectorAll('video'));" +
                "videos.sort(function(a,b){return b.clientWidth*b.clientHeight-a.clientWidth*a.clientHeight;});" +
                "var v=videos[0]||null;if(!v)return 'no-video';" +
                "var old=document.getElementById('__mytv_fullscreen_style');if(old)old.remove();" +
                "var style=document.createElement('style');style.id='__mytv_fullscreen_style';" +
                "style.textContent='html,body{width:100%!important;height:100%!important;margin:0!important;" +
                "padding:0!important;overflow:hidden!important;background:#000!important;}" +
                "body *{visibility:hidden!important;}[data-mytv-player]{visibility:visible!important;" +
                "opacity:1!important;clip-path:none!important;filter:none!important;}" +
                "video[data-mytv-video]{visibility:visible!important;display:block!important;" +
                "position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;" +
                "min-width:100vw!important;min-height:100vh!important;max-width:none!important;" +
                "max-height:none!important;margin:0!important;padding:0!important;z-index:2147483647!important;" +
                "object-fit:contain!important;background:#000!important;transform:none!important;}';" +
                "document.head.appendChild(style);" +
                "var node=v;while(node&&node!==document){node.setAttribute('data-mytv-player','1');" +
                "node=node.parentElement;}v.setAttribute('data-mytv-video','1');v.controls=false;" +
                "window.scrollTo(0,0);return 'fullscreen';})()"
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        exoPlayer = SimpleExoPlayer.Builder(requireContext()).build()
        exoPlayer?.setVideoSurfaceHolder(surfaceHolder)
        exoPlayer?.playWhenReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
    }
}
