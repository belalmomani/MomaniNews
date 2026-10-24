package com.momaninews.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.momaninews.app.ui.screens.admin.AdminAddNewsScreen
import com.momaninews.app.ui.screens.admin.AdminAdsScreen
import com.momaninews.app.ui.screens.admin.AdminCommentsScreen
import com.momaninews.app.ui.screens.admin.AdminHomeScreen
import com.momaninews.app.ui.screens.admin.AdminNewsScreen
import com.momaninews.app.ui.screens.admin.AdminNotifyScreen
import com.momaninews.app.ui.screens.admin.AdminUsersScreen
import com.momaninews.app.ui.screens.admin.AdminVerificationScreen
import com.momaninews.app.ui.screens.ads.AdRequestScreen
import com.momaninews.app.ui.screens.auth.SignInScreen
import com.momaninews.app.ui.screens.detail.NewsDetailScreen
import com.momaninews.app.ui.screens.home.HomeScreen
import com.momaninews.app.ui.screens.notifications.NotificationsScreen
import com.momaninews.app.ui.screens.profile.ProfileScreen
import com.momaninews.app.ui.screens.settings.SettingsScreen
import com.momaninews.app.ui.screens.splash.SplashScreen
import com.momaninews.app.ui.screens.verification.VerificationScreen

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
                onProfileClick = { navController.navigate(Routes.PROFILE) },
                onNotificationsClick = { navController.navigate(Routes.NOTIFICATIONS) },
                onAdRequestClick = { navController.navigate(Routes.AD_REQUEST) },
                onVerificationClick = { navController.navigate(Routes.VERIFICATION) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onAdminClick = { navController.navigate(Routes.ADMIN) },
                onSignInClick = { navController.navigate(Routes.SIGN_IN) },
                onComposeClick = { navController.navigate(Routes.POST_NEW) },
                onMyPostsClick = { navController.navigate(Routes.MY_POSTS) },
                onSavedClick = { navController.navigate(Routes.SAVED) }
            )
        }
        composable(Routes.POST_NEW) {
            com.momaninews.app.ui.screens.posts.PostComposerScreen(
                onBack = { navController.popBackStack() },
                onSignInClick = { navController.navigate(Routes.SIGN_IN) }
            )
        }
        composable(Routes.MY_POSTS) {
            com.momaninews.app.ui.screens.posts.MyPostsScreen(
                onBack = { navController.popBackStack() },
                onSignInClick = { navController.navigate(Routes.SIGN_IN) }
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
                onSignInClick = { navController.navigate(Routes.SIGN_IN) },
                onVerificationClick = { navController.navigate(Routes.VERIFICATION) },
                onNotificationsClick = { navController.navigate(Routes.NOTIFICATIONS) },
                onAdRequestClick = { navController.navigate(Routes.AD_REQUEST) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onAdminClick = { navController.navigate(Routes.ADMIN) }
            )
        }
        composable(Routes.SAVED) {
            com.momaninews.app.ui.screens.saved.SavedScreen(
                onBack = { navController.popBackStack() },
                onNewsClick = { id -> navController.navigate(Routes.newsDetail(id)) }
            )
        }
        composable(Routes.ADMIN) { AdminHomeScreen(onBack = { navController.popBackStack() }, onOpen = { navController.navigate(it) }) }
        composable(Routes.ADMIN_NEWS) { AdminNewsScreen { navController.popBackStack() } }
        composable(Routes.ADMIN_ADD_NEWS) { AdminAddNewsScreen { navController.popBackStack() } }
        composable(Routes.ADMIN_COMMENTS) { AdminCommentsScreen { navController.popBackStack() } }
        composable(Routes.ADMIN_VERIFICATION) { AdminVerificationScreen { navController.popBackStack() } }
        composable(Routes.ADMIN_ADS) { AdminAdsScreen { navController.popBackStack() } }
        composable(Routes.ADMIN_USERS) { AdminUsersScreen { navController.popBackStack() } }
        composable(Routes.ADMIN_STATS) { com.momaninews.app.ui.screens.admin.AdminStatsScreen { navController.popBackStack() } }
        composable(Routes.ADMIN_NOTIFY) { AdminNotifyScreen { navController.popBackStack() } }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.NOTIFICATIONS) {
            NotificationsScreen(
                onBack = { navController.popBackStack() },
                onNewsClick = { id -> navController.navigate(Routes.newsDetail(id)) },
                onSignInClick = { navController.navigate(Routes.SIGN_IN) }
            )
        }
        composable(Routes.AD_REQUEST) {
            AdRequestScreen(
                onBack = { navController.popBackStack() },
                onSignInClick = { navController.navigate(Routes.SIGN_IN) }
            )
        }
        composable(Routes.VERIFICATION) {
            VerificationScreen(onBack = { navController.popBackStack() })
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
