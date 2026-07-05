package com.nibawr.nav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            // FOREGROUND_SERVICE_TYPE_LOCATION requires fine location to already be
            // granted, so re-check the actual permission state rather than trusting
            // the callback map (POST_NOTIFICATIONS may be denied independently).
            if (hasFineLocation()) {
                startRecordingService()
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecorderScreen(
                        onStartClick = ::onStartClicked,
                        onStopClick = ::onStopClicked,
                    )
                }
            }
        }
    }

    private fun onStartClicked() {
        val needed = buildList {
            if (!hasFineLocation()) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (!hasNotifications()) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            startRecordingService()
        } else {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun onStopClicked() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun startRecordingService() {
        val intent = Intent(this, RecordingService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotifications(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

@Composable
private fun RecorderScreen(onStartClick: () -> Unit, onStopClick: () -> Unit) {
    val status by RecorderState.status.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = { if (status.isRecording) onStopClick() else onStartClick() },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) {
            Text(
                text = if (status.isRecording) "Stop Recording" else "Start Recording",
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (status.isRecording) {
            val elapsedSec = (SystemClock.elapsedRealtimeNanos() - status.startElapsedNs) / 1_000_000_000.0
            Text("Elapsed: ${formatElapsed(elapsedSec)}")

            Spacer(modifier = Modifier.height(16.dp))

            for (tag in status.eventCounts.keys) {
                val count = status.eventCounts[tag] ?: 0L
                val hz = status.hzByTag[tag] ?: 0.0
                Text("$tag: $count events  (${formatDecimal(hz, 1)} Hz)")
            }

            Spacer(modifier = Modifier.height(16.dp))

            val gps = status.lastGps
            if (gps != null) {
                Text(
                    "GPS: age=${gps.ageMs}ms  lat=${formatDecimal(gps.lat, 5)}  lon=${formatDecimal(gps.lon, 5)}  " +
                        "speed=${gps.speedMps?.let { formatDecimal(it.toDouble(), 2) } ?: "-"} m/s  " +
                        "hAcc=${gps.hAccM?.let { formatDecimal(it.toDouble(), 1) } ?: "-"} m",
                )
            } else {
                Text("GPS: no fix yet")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("File: ${status.fileName}")
            Text("Size: ${formatBytes(status.bytesWritten)}")
        }
    }
}

/** Locale-independent fixed-point formatting for on-screen display. */
private fun formatDecimal(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

private fun formatElapsed(totalSeconds: Double): String {
    val total = totalSeconds.toLong().coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024) return "${formatDecimal(kib, 1)} KiB"
    val mib = kib / 1024.0
    return "${formatDecimal(mib, 1)} MiB"
}
