package com.momaninews.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.momaninews.app.navigation.AppNavHost
import com.momaninews.app.ui.theme.MomaniNewsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MomaniNewsTheme {
                AppNavHost()
            }
        }
    }
}
