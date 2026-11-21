package io.github.kevinnortier.isstracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.kevinnortier.isstracker.ui.live.LiveRoute
import io.github.kevinnortier.isstracker.ui.theme.ISSTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ISSTrackerTheme {
                LiveRoute()
            }
        }
    }
}
