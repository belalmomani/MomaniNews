package com.momaninews.app

import android.app.Application
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.notifications.PushNotifier
import com.momaninews.app.data.settings.SettingsStore

class MomaniNewsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        com.momaninews.app.data.settings.FollowStore.init(this)
        com.momaninews.app.data.settings.PostPrefs.init(this)
        AuthRepositories.init(this)
        PushNotifier.createChannel(this)
        PushNotifier.schedule(this)
    }
}
