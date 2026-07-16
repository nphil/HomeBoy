package com.homeboy.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.data.ConnectionMonitor
import com.homeboy.app.data.ConnectionState
import kotlinx.coroutines.launch

/**
 * Top-bar connection indicator, shown on every tab:
 *  - green cloud-check   → connected to the Homebox server
 *  - red cloud-off       → offline (badge shows how many local changes wait to sync)
 *  - spinning amber sync → sync in progress
 * Tapping it explains the state and offers a manual "Sync now".
 */
@Composable
fun ConnectionStatusAction() {
    val state by ConnectionMonitor.state.collectAsStateWithLifecycle()
    val pending by ConnectionMonitor.pendingCount.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    val onlineGreen = Color(0xFF4CAF50)
    val syncAmber = Color(0xFFFFA726)

    IconButton(onClick = { showDialog = true }) {
        BadgedBox(badge = {
            if (pending > 0) Badge { Text(pending.toString()) }
        }) {
            when (state) {
                ConnectionState.SYNCING -> {
                    val transition = rememberInfiniteTransition(label = "syncSpin")
                    val angle by transition.animateFloat(
                        initialValue = 360f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
                        label = "syncAngle"
                    )
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = "Syncing with server",
                        tint = syncAmber,
                        modifier = Modifier.rotate(angle)
                    )
                }
                ConnectionState.OFFLINE -> Icon(
                    Icons.Default.CloudOff,
                    contentDescription = "Offline",
                    tint = MaterialTheme.colorScheme.error
                )
                ConnectionState.ONLINE -> Icon(
                    Icons.Default.CloudDone,
                    contentDescription = "Connected",
                    tint = onlineGreen
                )
            }
        }
    }

    if (showDialog) {
        val app = LocalContext.current.applicationContext as HomeboxApplication
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(
                    when (state) {
                        ConnectionState.ONLINE -> "Connected"
                        ConnectionState.OFFLINE -> "Offline"
                        ConnectionState.SYNCING -> "Syncing…"
                    }
                )
            },
            text = {
                val pendingLine = when {
                    pending == 0 -> ""
                    pending == 1 -> "\n\n1 local change is waiting to sync."
                    else -> "\n\n$pending local changes are waiting to sync."
                }
                Text(
                    when (state) {
                        ConnectionState.ONLINE ->
                            "Connected to your Homebox server. Changes save directly." + pendingLine
                        ConnectionState.OFFLINE ->
                            "The server can't be reached right now. You're viewing locally " +
                                "stored data — keep working, and everything you change is saved " +
                                "on this device and synced automatically when the connection " +
                                "returns." + pendingLine
                        ConnectionState.SYNCING ->
                            "Syncing local changes with your Homebox server…" + pendingLine
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    scope.launch { app.repository.syncNow() }
                }) { Text("Sync now") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Close") }
            }
        )
    }
}
