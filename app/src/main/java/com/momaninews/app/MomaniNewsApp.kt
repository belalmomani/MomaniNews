package com.momaninews.app

import android.app.Application
import com.momaninews.app.data.auth.AuthRepositories

class MomaniNewsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthRepositories.init(this)
    }
}
