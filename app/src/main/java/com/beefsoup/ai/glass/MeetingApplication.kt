package com.beefsoup.ai.glass

import android.app.Application

class MeetingApplication : Application() {
    val runtime: MeetingRuntime by lazy { MeetingRuntime(this) }
}
