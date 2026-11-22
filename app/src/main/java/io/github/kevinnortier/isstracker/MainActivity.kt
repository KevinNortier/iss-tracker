package io.github.kevinnortier.isstracker

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.kevinnortier.isstracker.data.settings.AppSettings
import io.github.kevinnortier.isstracker.ui.aim.AimRoute
import io.github.kevinnortier.isstracker.ui.live.LiveRoute
import io.github.kevinnortier.isstracker.ui.passdetails.PassDetailsRoute
import io.github.kevinnortier.isstracker.ui.passes.PassesRoute
import io.github.kevinnortier.isstracker.ui.satellites.AddSatelliteRoute
import io.github.kevinnortier.isstracker.ui.satellites.SatellitesRoute
import io.github.kevinnortier.isstracker.ui.settings.SettingsRoute
import io.github.kevinnortier.isstracker.ui.theme.ISSTrackerTheme
import io.github.kevinnortier.isstracker.ui.theme.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsFlow = (application as IssTrackerApp).container.settingsRepository.settings
        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
            val dark = settings.themeMode.isDark()
            // Status/navigation bar icons must contrast with the app's theme, which may differ from the system's.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            ISSTrackerTheme(mode = settings.themeMode) {
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
    const val SATELLITES = "satellites"
    const val ADD_SATELLITE = "satellites/add"
    const val PASS_DETAILS = "pass/{norad}/{rise}"

    fun passDetails(noradId: Int, riseMillis: Long) = "pass/$noradId/$riseMillis"
}

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()
    val openSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } }
    val openAim = { navController.navigate(Routes.AIM) { launchSingleTop = true } }
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(navController = navController, startDestination = Routes.PASSES) {
        composable(Routes.PASSES) {
            PassesRoute(
                onOpenLive = { navController.navigate(Routes.LIVE) { launchSingleTop = true } },
                onOpenAim = openAim,
                onOpenSettings = openSettings,
                onOpenSatellites = { navController.navigate(Routes.SATELLITES) { launchSingleTop = true } },
                onOpenPass = { noradId, riseMillis -> navController.navigate(Routes.passDetails(noradId, riseMillis)) },
            )
        }
        composable(
            Routes.PASS_DETAILS,
            arguments = listOf(
                navArgument("norad") { type = NavType.IntType },
                navArgument("rise") { type = NavType.LongType },
            ),
        ) { entry ->
            PassDetailsRoute(
                noradId = entry.arguments?.getInt("norad") ?: 0,
                riseMillis = entry.arguments?.getLong("rise") ?: 0L,
                onBack = back,
                onOpenAim = openAim,
            )
        }
        composable(Routes.LIVE) {
            LiveRoute(onBack = back, onOpenAim = openAim, onOpenSettings = openSettings)
        }
        composable(Routes.AIM) {
            AimRoute(onBack = back)
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onBack = back,
                onOpenSatellites = { navController.navigate(Routes.SATELLITES) { launchSingleTop = true } },
            )
        }
        composable(Routes.SATELLITES) {
            SatellitesRoute(
                onBack = back,
                onAdd = { navController.navigate(Routes.ADD_SATELLITE) { launchSingleTop = true } },
            )
        }
        composable(Routes.ADD_SATELLITE) {
            AddSatelliteRoute(onBack = back)
        }
    }
}
