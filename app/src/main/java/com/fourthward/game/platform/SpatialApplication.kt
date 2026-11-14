package com.fourthward.game.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.fourthward.game.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
