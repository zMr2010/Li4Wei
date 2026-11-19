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
        bridge = VrGameBridge(this, savedInstanceState?.getBoolean(STARTUP_PLAYED) == true)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (checkSelfPermission(PICO_HAND_TRACKING) != PackageManager.PERMISSION_GRANTED &&
            canRequestPicoHandTracking()
        ) {
            requestPermissions(arrayOf(PICO_HAND_TRACKING), 41)
        }
    }

    // Both vendors' permissions are declared in the release APK. Only PICO defines this one.
    @Suppress("DEPRECATION")
    private fun canRequestPicoHandTracking(): Boolean {
        try {
            packageManager.getPermissionInfo(PICO_HAND_TRACKING, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return PICO_HAND_TRACKING in packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STARTUP_PLAYED, bridge.startupPlayed)
    }

    private companion object {
        const val PICO_HAND_TRACKING = "com.picovr.permission.HAND_TRACKING"
        const val STARTUP_PLAYED = "music_startup_played"
    }
}
