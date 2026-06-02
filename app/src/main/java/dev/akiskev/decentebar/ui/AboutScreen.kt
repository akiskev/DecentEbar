package dev.akiskev.decentebar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val PAYPAL_DONATION_URL =
    "https://www.paypal.com/donate/?business=akiskev%40gmail.com&item_name=Decent%20E-Bar&currency_code=USD"

@Composable
internal fun AboutScreen() {
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Decent E-Bar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("v0.0.2.1", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Automates espresso pressure profiling on the Decent E-Bar by reading live weight and flow via Android Accessibility and sliding the pressure bar to precise positions.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        item {
            HorizontalDivider()
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Safety", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text(
                    "This app dispatches real gestures to live hardware. Always keep the E-Bar within reach. Use E-Stop or Disarm immediately if the shot behaves unexpectedly. Never leave an armed session unattended.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            HorizontalDivider()
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Feedback & Support", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "This is a beta release. Bugs, unexpected behaviour, and profile sharing are all welcome — please include an exported shot log when reporting issues.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { uriHandler.openUri("mailto:akiskev@gmail.com?subject=Decent%20E-Bar%20Feedback") },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("akiskev@gmail.com", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            HorizontalDivider()
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Support Development", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Donations help support continued development and testing.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { uriHandler.openUri(PAYPAL_DONATION_URL) },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Donate with PayPal", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            HorizontalDivider()
        }
        item {
            TextButton(
                onClick = { uriHandler.openUri("https://akiskev.dev") },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    "akiskev.dev",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
