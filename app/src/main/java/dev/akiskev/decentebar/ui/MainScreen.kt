package dev.akiskev.decentebar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class AppTab(val label: String, val icon: ImageVector) {
    CONTROL("Control", Icons.Default.PlayArrow),
    PROFILE("Profile", Icons.Default.Tune),
    LUT("LUT", Icons.Default.TableChart),
    DEBUG("Debug", Icons.Default.BugReport),
    LOG("Log", Icons.Default.Assessment),
    ABOUT("About", Icons.Default.Info)
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    openAccessibilitySettings: () -> Unit,
    connectToScale: () -> Unit = {},
    disconnectScale: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(AppTab.CONTROL) }

    // LUT and Debug are developer-only tabs. If dev mode is turned off while one is selected,
    // fall back to Control so we never render a tab that has no rail entry.
    LaunchedEffect(state.devMode) {
        if (!state.devMode && (selectedTab == AppTab.LUT || selectedTab == AppTab.DEBUG)) {
            selectedTab = AppTab.CONTROL
        }
    }

    Scaffold { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            NavigationRail {
                val mainTabs = AppTab.entries.filter { tab ->
                    tab != AppTab.ABOUT &&
                        (state.devMode || (tab != AppTab.LUT && tab != AppTab.DEBUG))
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Top
                ) {
                    mainTabs.forEach { tab ->
                        NavigationRailItem(
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab }
                        )
                    }
                }
                NavigationRailItem(
                    icon = { Icon(AppTab.ABOUT.icon, contentDescription = AppTab.ABOUT.label) },
                    label = { Text(AppTab.ABOUT.label) },
                    selected = selectedTab == AppTab.ABOUT,
                    onClick = { selectedTab = AppTab.ABOUT }
                )
            }
            VerticalDivider()
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Header(state)
                if (!state.serviceEnabled) {
                    AccessibilityWarningBanner(onEnable = openAccessibilitySettings)
                }
                when (selectedTab) {
                    AppTab.CONTROL -> ControlScreen(state, viewModel, connectToScale, disconnectScale)
                    AppTab.PROFILE -> ProfileScreen(state, viewModel)
                    AppTab.LUT -> LutScreen(state, viewModel)
                    AppTab.DEBUG -> DebugScreen(state)
                    AppTab.LOG -> LogScreen(state, viewModel)
                    AppTab.ABOUT -> AboutScreen(
                        devMode = state.devMode,
                        onDevModeChange = viewModel::setDevMode
                    )
                }
            }
        }
    }
}
