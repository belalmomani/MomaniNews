package com.momaninews.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.momaninews.app.data.notifications.PushNotifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.momaninews.app.navigation.AppNavHost
import com.momaninews.app.ui.theme.MomaniNewsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !PushNotifier.hasPermission(this)) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        com.momaninews.app.data.notifications.PushTopics.sync(this)
        setContent {
            MomaniNewsTheme {
                AppNavHost()
            }
        }
    }
}
