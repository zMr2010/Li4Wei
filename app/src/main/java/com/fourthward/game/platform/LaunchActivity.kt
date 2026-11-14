package com.fourthward.game.platform

import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import android.os.Bundle
import android.content.pm.PackageManager

class LaunchActivity : SpatialLaunchActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(HAND_TRACKING) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(HAND_TRACKING), 41)
        }
    }

    companion object {
        const val HAND_TRACKING = "com.picovr.permission.HAND_TRACKING"
    }
}
