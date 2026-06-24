package dev.akiskev.decentebar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.akiskev.decentebar.ui.MainScreen
import dev.akiskev.decentebar.ui.MainViewModel
import dev.akiskev.decentebar.ui.DecentebarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            DecentebarTheme(themeMode = state.themeMode) {
                AppEntry(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun AppEntry(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current

    // Re-check whether the accessibility service is enabled every time the app resumes — this fires
    // on first launch and again when the user returns from the system Accessibility settings screen,
    // so the warning banner appears/clears immediately without waiting for a service rebind.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshServiceEnabled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) viewModel.connectToScale()
    }

    MainScreen(
        viewModel = viewModel,
        openAccessibilitySettings = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        connectToScale = {
            val allGranted = blePermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) viewModel.connectToScale() else permissionLauncher.launch(blePermissions)
        },
        disconnectScale = viewModel::disconnectScale
    )
}
