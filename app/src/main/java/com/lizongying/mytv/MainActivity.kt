package com.lizongying.mytv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.lizongying.mytv.models.TVViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch


class MainActivity : FragmentActivity(), Request.RequestListener {

    private val readyFragments = mutableSetOf<String>()
    private var networkReady = false
    private var initialContentStarted = false
    private val playerFragment = PlayerFragment()
    private val mainFragment = MainFragment()
    private val infoFragment = InfoFragment()
    private val channelFragment = ChannelFragment()
    private var timeFragment = TimeFragment()
    private val settingFragment = SettingFragment()
    private val errorFragment = ErrorFragment()

    private var doubleBackToExitPressedOnce = false

    private val handler = Handler(Looper.getMainLooper())
    private val delayHideSetting: Long = 10000

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        lifecycleScope.launch(Dispatchers.IO) {
            val utilsJob = async(start = CoroutineStart.LAZY) { Utils.init() }

            utilsJob.start()

//            utilsJob.await()
        }

        setContentView(R.layout.activity_main)

        Request.setRequestListener(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = SYSTEM_UI_FLAG_HIDE_NAVIGATION

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, playerFragment)
                .add(R.id.main_browse_fragment, timeFragment)
                .add(R.id.main_browse_fragment, infoFragment)
                .add(R.id.main_browse_fragment, channelFragment)
                .add(R.id.main_browse_fragment, mainFragment)
                .hide(mainFragment)
                .commit()
        }
        errorFragment.buttonClickListener = View.OnClickListener {
            supportFragmentManager.beginTransaction()
                .remove(errorFragment)
                .commit()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.registerDefaultNetworkCallback(object :
                ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.i(TAG, "net ${Build.VERSION.SDK_INT}")
                    if (this@MainActivity.isNetworkConnected) {
                        Log.i(TAG, "net isNetworkConnected")
                        markNetworkReady()
                    }
                }
            })
        } else {
            Log.i(TAG, "net ${Build.VERSION.SDK_INT}")
            markNetworkReady()
        }

    }

    fun showInfoFragment(tvViewModel: TVViewModel) {
        infoFragment.show(tvViewModel)
        if (SP.channelNum) {
            channelFragment.show(tvViewModel)
        }
    }

    private fun showChannel(channel: String) {
        if (!mainFragment.isHidden) {
            return
        }

        if (settingFragment.isVisible) {
            return
        }

        if (SP.channelNum) {
            channelFragment.show(channel)
        }
    }

    fun play(tvViewModel: TVViewModel) {
        playerFragment.play(tvViewModel)
        mainFragment.view?.requestFocus()
    }

    fun play(itemPosition: Int) {
        mainFragment.play(itemPosition)
    }

    fun prev() {
        mainFragment.prev()
    }

    fun next() {
        mainFragment.next()
    }

    private fun prevSource() {
//        mainFragment.prevSource()
    }

    fun nextSource() {
        mainFragment.nextSource()
    }

    fun selectSource(index: Int) {
        mainFragment.selectSource(index)
    }

    fun markStableSource(index: Int) {
        mainFragment.markStableSource(index)
    }

    fun clearStableSource() {
        mainFragment.clearStableSource()
    }

    fun switchMainFragment() {
        val transaction = supportFragmentManager.beginTransaction()

        if (mainFragment.isHidden) {
            transaction.show(mainFragment)
            transaction.runOnCommit { mainFragment.revealCurrentChannel() }
            // A touch list must stay visible while the user scrolls. It is dismissed by tapping
            // outside the panel, choosing a channel, or pressing Back instead of a timer.
        } else {
            transaction.hide(mainFragment)
        }

        transaction.commit()
    }

    fun settingDelayHide() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, delayHideSetting)
        showTime()
    }

    fun settingHideNow() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, 0)
    }

    fun settingNeverHide() {
        handler.removeCallbacks(hideSetting)
    }

    private fun mainFragmentIsHidden(): Boolean {
        return mainFragment.isHidden
    }

    private fun hideMainFragment() {
        if (!mainFragment.isHidden) {
            supportFragmentManager.beginTransaction()
                .hide(mainFragment)
                .commit()
        }
    }

    fun fragmentReady(tag: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { fragmentReady(tag) }
            return
        }

        if (!readyFragments.add(tag)) {
            return
        }
        Log.i(TAG, "ready $tag ${readyFragments.size}")
        startInitialContentWhenReady()
    }

    /**
     * Connectivity callbacks run outside the UI thread.  Defer all fragment and view work to
     * the main looper, otherwise startup can terminate with CalledFromWrongThreadException.
     */
    private fun markNetworkReady() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { markNetworkReady() }
            return
        }

        networkReady = true
        startInitialContentWhenReady()
    }

    private fun startInitialContentWhenReady() {
        if (initialContentStarted || !networkReady || readyFragments.size < REQUIRED_FRAGMENT_COUNT) {
            return
        }

        initialContentStarted = true
        Log.i(TAG, "initial content ready")
        if (mainFragment.isAdded) {
            mainFragment.fragmentReady()
            showTime()
        }
    }

    private fun showTime() {
        Log.i(TAG, "showTime ${SP.time}")
        if (SP.time) {
            timeFragment.show()
        } else {
            timeFragment.hide()
        }
    }

    fun isPlaying() {
        if (errorFragment.isVisible) {
            supportFragmentManager.beginTransaction()
                .remove(errorFragment)
                .commit()
        }
    }

    private fun showSetting() {
        if (!mainFragment.isHidden) {
            return
        }

        Log.i(TAG, "settingFragment ${settingFragment.isVisible}")
        if (!settingFragment.isVisible) {
            settingFragment.show(supportFragmentManager, "setting")
            settingDelayHide()
        } else {
            handler.removeCallbacks(hideSetting)
            settingFragment.dismiss()
        }
    }

    private val hideSetting = Runnable {
        if (settingFragment.isVisible) {
            settingFragment.dismiss()
        }
    }

    private fun channelUp() {
        if (mainFragment.isHidden) {
            if (SP.channelReversal) {
                next()
                return
            }
            prev()
        } else {
//                    if (mainFragment.selectedPosition == 0) {
//                        mainFragment.setSelectedPosition(
//                            mainFragment.tvListViewModel.maxNum.size - 1,
//                            false
//                        )
//                    }
        }
    }

    private fun channelDown() {
        if (mainFragment.isHidden) {
            if (SP.channelReversal) {
                prev()
                return
            }
            next()
        } else {
//                    if (mainFragment.selectedPosition == mainFragment.tvListViewModel.maxNum.size - 1) {
////                        mainFragment.setSelectedPosition(0, false)
//                        hideMainFragment()
//                        return false
//                    }
        }
    }

    private fun back() {
        if (!mainFragmentIsHidden()) {
            hideMainFragment()
            return
        }

        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            return
        }

        doubleBackToExitPressedOnce = true
        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            doubleBackToExitPressedOnce = false
        }, 2000)
    }

    /**
     * WebView consumes keyboard digits before Activity.onKeyDown. Intercept only channel-number
     * keys here so remote and hardware-keyboard channel selection keeps working on official pages.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                showChannel((event.keyCode - KeyEvent.KEYCODE_0).toString())
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.i(TAG, "keyCode $keyCode, event $event")
        when (keyCode) {
            KeyEvent.KEYCODE_0 -> {
                showChannel("0")
                return true
            }

            KeyEvent.KEYCODE_1 -> {
                showChannel("1")
                return true
            }

            KeyEvent.KEYCODE_2 -> {
                showChannel("2")
                return true
            }

            KeyEvent.KEYCODE_3 -> {
                showChannel("3")
                return true
            }

            KeyEvent.KEYCODE_4 -> {
                showChannel("4")
                return true
            }

            KeyEvent.KEYCODE_5 -> {
                showChannel("5")
                return true
            }

            KeyEvent.KEYCODE_6 -> {
                showChannel("6")
                return true
            }

            KeyEvent.KEYCODE_7 -> {
                showChannel("7")
                return true
            }

            KeyEvent.KEYCODE_8 -> {
                showChannel("8")
                return true
            }

            KeyEvent.KEYCODE_9 -> {
                showChannel("9")
                return true
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BOOKMARK -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_UNKNOWN -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_HELP -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_SETTINGS -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_ENTER -> {
                switchMainFragment()
            }

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                switchMainFragment()
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!mainFragment.isVisible && !settingFragment.isVisible) {
                    switchMainFragment()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!mainFragment.isVisible && !settingFragment.isVisible) {
                    showSetting()
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun getAppSignature() = this.appSignature

    override fun onStart() {
        Log.i(TAG, "onStart")
        super.onStart()
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        Request.onDestroy()
    }

    override fun onRequestFinished(message: String?) {
        if (message != null && !errorFragment.isVisible) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, errorFragment)
                .commitNow()
            errorFragment.setErrorContent(message)
        }
        fragmentReady("Request")
    }

    private companion object {
        const val TAG = "MainActivity"
        const val REQUIRED_FRAGMENT_COUNT = 5
    }
}
