package dev.akiskev.decentebar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class AppTab(val label: String, val icon: ImageVector) {
    CONTROL("Control", Icons.Default.PlayArrow),
    PROFILE("Profile", Icons.Default.Tune),
    LUT("LUT", Icons.Default.TableChart),
    DEBUG("Debug", Icons.Default.BugReport),
    LOG("Log", Icons.Default.Assessment),
    ABOUT("About", Icons.Default.Info)
}

private val AppRailWidth = 76.dp
private val AppRailIconPillWidth = 52.dp
private val AppRailIconPillHeight = 32.dp
private val AppRailItemMinHeight = 62.dp

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    openAccessibilitySettings: () -> Unit,
    connectToScale: () -> Unit = {},
    disconnectScale: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.CONTROL.name) }
    val selectedTab = AppTab.entries.firstOrNull { it.name == selectedTabName } ?: AppTab.CONTROL
    var supportMenuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun selectTab(tab: AppTab) {
        selectedTabName = tab.name
    }

    // LUT and Debug are developer-only tabs. If dev mode is turned off while one is selected,
    // fall back to Control so we never render a tab that has no rail entry.
    LaunchedEffect(state.devMode) {
        if (!state.devMode && (selectedTab == AppTab.LUT || selectedTab == AppTab.DEBUG)) {
            selectedTabName = AppTab.CONTROL.name
        }
    }

    LaunchedEffect(
        state.profileMessage,
        state.lutMessage,
        state.logMessage,
        state.importShotLogMessage
    ) {
        listOf(
            state.profileMessage,
            state.lutMessage,
            state.logMessage,
            state.importShotLogMessage
        )
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Surface(
                modifier = Modifier.width(AppRailWidth),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                val mainTabs = listOf(AppTab.CONTROL, AppTab.PROFILE, AppTab.LOG)
                val developerTabs = listOf(AppTab.LUT, AppTab.DEBUG).filter { state.devMode }
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(top = 8.dp, bottom = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
                    ) {
                        mainTabs.forEach { tab ->
                            AppRailItem(
                                tab = tab,
                                selected = selectedTab == tab,
                                onClick = { selectTab(tab) }
                            )
                        }
                        if (developerTabs.isNotEmpty()) {
                            Text(
                                "Dev",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                            )
                            developerTabs.forEach { tab ->
                                AppRailItem(
                                    tab = tab,
                                    selected = selectedTab == tab,
                                    onClick = { selectTab(tab) }
                                )
                            }
                        }
                    }

                    Box(Modifier.fillMaxWidth()) {
                        RailButton(
                            label = "Support",
                            icon = Icons.Default.Favorite,
                            selected = false,
                            onClick = { supportMenuExpanded = true },
                            contentDescription = "Support menu",
                            unselectedTint = MaterialTheme.colorScheme.secondary
                        )
                        DropdownMenu(
                            expanded = supportMenuExpanded,
                            onDismissRequest = { supportMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("PayPal") },
                                onClick = {
                                    supportMenuExpanded = false
                                    uriHandler.openUri(PAYPAL_DONATION_URL)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Ko-fi") },
                                onClick = {
                                    supportMenuExpanded = false
                                    uriHandler.openUri(KOFI_DONATION_URL)
                                }
                            )
                        }
                    }
                    AppRailItem(
                        tab = AppTab.ABOUT,
                        selected = selectedTab == AppTab.ABOUT,
                        onClick = { selectTab(AppTab.ABOUT) }
                    )
                }
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
                        themeMode = state.themeMode,
                        onThemeModeChange = viewModel::setThemeMode,
                        devMode = state.devMode,
                        onDevModeChange = viewModel::setDevMode
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRailItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    RailButton(
        label = tab.label,
        icon = tab.icon,
        selected = selected,
        onClick = onClick,
        contentDescription = "${tab.label} tab"
    )
}

@Composable
private fun RailButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    unselectedTint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = if (selected) colors.onPrimaryContainer else unselectedTint
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppRailItemMinHeight)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .width(AppRailIconPillWidth)
                .height(AppRailIconPillHeight)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(if (selected) colors.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = contentColor)
        }
        Text(
            label,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
        )
    }
}
