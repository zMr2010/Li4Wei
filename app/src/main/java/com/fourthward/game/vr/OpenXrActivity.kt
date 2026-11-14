package com.fourthward.game.vr

import android.app.NativeActivity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.annotation.Keep

@Keep
class OpenXrActivity : NativeActivity() {
    private lateinit var bridge: VrGameBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        bridge = VrGameBridge(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (checkSelfPermission(HAND_TRACKING) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(HAND_TRACKING), 41)
        }
    }

    @Keep
    fun getGameBridge(): VrGameBridge = bridge

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        bridge.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        bridge.setFocused(hasFocus)
    }

    override fun onPause() {
        bridge.setResumed(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        bridge.setResumed(true)
    }

    override fun onDestroy() {
        bridge.close()
        super.onDestroy()
    }

    private companion object {
        const val HAND_TRACKING = "com.picovr.permission.HAND_TRACKING"
    }
}
