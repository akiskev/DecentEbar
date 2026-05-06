package dev.akiskev.decentebar

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.akiskev.decentebar.ui.MainScreen
import dev.akiskev.decentebar.ui.MainViewModel
import dev.akiskev.decentebar.ui.DecentebarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DecentebarTheme {
                AppEntry()
            }
        }
    }
}

@Composable
private fun AppEntry(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    MainScreen(
        viewModel = viewModel,
        openAccessibilitySettings = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    )
}
