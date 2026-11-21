package io.github.kevinnortier.isstracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.kevinnortier.isstracker.ui.live.LiveRoute
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
    const val LIVE = "live"
    const val SETTINGS = "settings"
}

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LIVE) {
        composable(Routes.LIVE) {
            LiveRoute(onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }
    }
}
