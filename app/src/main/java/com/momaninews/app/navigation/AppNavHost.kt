package com.momaninews.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.momaninews.app.ui.screens.auth.SignInScreen
import com.momaninews.app.ui.screens.detail.NewsDetailScreen
import com.momaninews.app.ui.screens.home.HomeScreen
import com.momaninews.app.ui.screens.profile.ProfileScreen
import com.momaninews.app.ui.screens.splash.SplashScreen

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onNewsClick = { newsId ->
                    navController.navigate(Routes.newsDetail(newsId))
                },
                onProfileClick = { navController.navigate(Routes.PROFILE) }
            )
        }
        composable(
            route = Routes.NEWS_DETAIL,
            arguments = listOf(
                navArgument(Routes.ARG_NEWS_ID) { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val newsId = backStackEntry.arguments?.getInt(Routes.ARG_NEWS_ID)
            if (newsId != null) {
                NewsDetailScreen(
                    newsId = newsId,
                    onBack = { navController.popBackStack() },
                    onSignInClick = { navController.navigate(Routes.SIGN_IN) }
                )
            }
        }
        composable(Routes.PROFILE) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onSignInClick = { navController.navigate(Routes.SIGN_IN) }
            )
        }
        composable(Routes.SIGN_IN) {
            SignInScreen(
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() }
            )
        }
        // مسارات المراحل التالية (طلب التوثيق، الإشعارات، الإعلانات...) تُضاف هنا لاحقاً.
    }
}
