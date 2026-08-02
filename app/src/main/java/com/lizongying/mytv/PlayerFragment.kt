package com.lizongying.mytv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
    private var tvViewModel: TVViewModel? = null
    private val aspectRatio = 16f / 9f

    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private var exoPlayer: SimpleExoPlayer? = null

    // Touch overlay
    private var touchOverlay: View? = null
    private var channelNameText: TextView? = null
    private var controlsVisible = true
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
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
        setupTouchControls()

        playerView?.viewTreeObserver?.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    playerView?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                    playerView?.player = activity?.let {
                        ExoPlayer.Builder(it)
                            .build()
                    }
                    playerView?.player?.playWhenReady = true
                    playerView?.player?.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            val vp = playerView ?: return
                            val ratio = vp.measuredWidth.toFloat() / vp.measuredHeight.toFloat()
                            val layoutParams = vp.layoutParams
                            if (ratio < aspectRatio) {
                                layoutParams.height =
                                    (vp.measuredWidth / aspectRatio).toInt()
                                vp.layoutParams = layoutParams
                            } else if (ratio > aspectRatio) {
                                layoutParams.width =
                                    (vp.measuredHeight * aspectRatio).toInt()
                                vp.layoutParams = layoutParams
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "PlaybackException $error")
                            tvViewModel?.changed()
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying) {
                                (activity as? MainActivity)?.isPlaying()
                            }
                        }
                    })
                }
            })
        (activity as MainActivity).fragmentReady("PlayerFragment")
        return _binding!!.root
    }

    private fun setupTouchControls() {
        val btnPrev = touchOverlay?.findViewById<View>(R.id.btn_prev)
        val btnNext = touchOverlay?.findViewById<View>(R.id.btn_next)
        val btnSettings = touchOverlay?.findViewById<View>(R.id.btn_settings)
        val centerArea = touchOverlay?.findViewById<View>(R.id.center_tap_area)

        btnPrev?.setOnClickListener {
            (activity as? MainActivity)?.prev()
            showControlsTemporarily()
        }

        btnNext?.setOnClickListener {
            (activity as? MainActivity)?.next()
            showControlsTemporarily()
        }

        btnSettings?.setOnClickListener {
            // Trigger settings via MainActivity
            val event = android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_MENU
            )
            activity?.dispatchKeyEvent(event)
            showControlsTemporarily()
        }

        centerArea?.setOnClickListener {
            // Tap center to show/hide channel list
            (activity as? MainActivity)?.switchMainFragment()
        }

        // Tap on overlay to toggle controls
        touchOverlay?.setOnClickListener {
            toggleControls()
        }

        showControlsTemporarily()
    }

    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControlsTemporarily()
        }
    }

    private fun showControlsTemporarily() {
        touchOverlay?.visibility = View.VISIBLE
        controlsVisible = true
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, autoHideDelayMs)
    }

    private fun hideControls() {
        touchOverlay?.visibility = View.GONE
        controlsVisible = false
    }

    @OptIn(UnstableApi::class)
    fun play(tvViewModel: TVViewModel) {
        this.tvViewModel = tvViewModel
        // Update channel name display
        channelNameText?.text = tvViewModel.getTV().title
        showControlsTemporarily()

        playerView?.player?.run {
            setMediaItem(MediaItem.fromUri(tvViewModel.getVideoUrlCurrent()))
            prepare()
        }
        exoPlayer?.run {
            setMediaItem(com.google.android.exoplayer2.MediaItem.fromUri(tvViewModel.getVideoUrlCurrent()))
            prepare()
        }
    }

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
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
        showControlsTemporarily()
    }

    override fun onPause() {
        super.onPause()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
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
        if (playerView != null) {
            playerView!!.player?.release()
        }
        exoPlayer?.release()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "PlayerFragment"
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
