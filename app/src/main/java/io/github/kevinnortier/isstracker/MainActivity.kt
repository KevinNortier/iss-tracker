package io.github.kevinnortier.isstracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.kevinnortier.isstracker.ui.aim.AimRoute
import io.github.kevinnortier.isstracker.ui.live.LiveRoute
import io.github.kevinnortier.isstracker.ui.passes.PassesRoute
import io.github.kevinnortier.isstracker.ui.settings.SettingsRoute
import io.github.kevinnortier.isstracker.ui.theme.ISSTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ISSTrackerTheme {
                AppNavigation()
            }
        }
    }
}

private object Routes {
    const val PASSES = "passes"
    const val LIVE = "live"
    const val SETTINGS = "settings"
    const val AIM = "aim"
}

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()
    val openSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } }
    val openAim = { navController.navigate(Routes.AIM) { launchSingleTop = true } }
    NavHost(navController = navController, startDestination = Routes.PASSES) {
        composable(Routes.PASSES) {
            PassesRoute(
                onOpenLive = { navController.navigate(Routes.LIVE) { launchSingleTop = true } },
                onOpenAim = openAim,
                onOpenSettings = openSettings,
            )
        }
        composable(Routes.LIVE) {
            LiveRoute(onBack = { navController.popBackStack() }, onOpenAim = openAim, onOpenSettings = openSettings)
        }
        composable(Routes.AIM) {
            AimRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }
    }
}
