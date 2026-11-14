package com.fourthward.game.platform

import android.app.Application

/** Native OpenXR owns presentation. Do not start the retained Spatial SDK application. */
class OpenXrApplication : Application()
