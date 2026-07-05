package com.nibawr.nav

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that logs raw IMU + GNSS samples to a CSV file on
 * external storage. All sensor/location callbacks and all file I/O happen
 * on a single dedicated [HandlerThread] ("sensors") — nothing touches the
 * main thread.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_STOP = "com.nibawr.nav.action.STOP"
        private const val TAG = "NavRecorder"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val SNAPSHOT_INTERVAL_MS = 500L
        private const val WRITE_BUFFER_BYTES = 1 shl 20 // 1 MiB

        /** Sensor types to record, in the order they should be registered/logged, with their CSV tag. */
        private val SENSOR_TAGS: List<Pair<Int, String>> = listOf(
            Sensor.TYPE_ACCELEROMETER to "acc",
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED to "acu",
            Sensor.TYPE_GYROSCOPE to "gyr",
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED to "gyu",
            Sensor.TYPE_LINEAR_ACCELERATION to "lac",
            Sensor.TYPE_ROTATION_VECTOR to "rot",
            Sensor.TYPE_MAGNETIC_FIELD to "mag",
            Sensor.TYPE_PRESSURE to "prs",
        )

        private val SENSOR_TYPE_TO_TAG: Map<Int, String> = SENSOR_TAGS.toMap()
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var writer: BufferedWriter? = null
    private var outputFile: File? = null

    // Main-thread-only: guards against duplicate start intents.
    private var startRequested = false

    // Handler-thread-confined state (single-threaded looper, so no locking
    // needed) — including the writer, wake lock, and recording flag. Nothing
    // below this line may be touched from the main thread.
    private var recording = false
    private var startElapsedNs = 0L
    private val eventCounts = LinkedHashMap<String, Long>()
    private var lastGpsFix: Location? = null
    private var bytesWritten = 0L

    private val flushRunnable = object : Runnable {
        override fun run() {
            try {
                writer?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "flush failed", e)
            }
            handler.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    private val snapshotRunnable = object : Runnable {
        override fun run() {
            postSnapshot()
            handler.postDelayed(this, SNAPSHOT_INTERVAL_MS)
        }
    }

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val tag = SENSOR_TYPE_TO_TAG[event.sensor.type] ?: return
            writeImuRow(tag, event)
            eventCounts[tag] = (eventCounts[tag] ?: 0L) + 1L
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // No-op: accuracy is recorded per-event in the CSV row itself.
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            writeGpsRow(location)
            eventCounts["gps"] = (eventCounts["gps"] ?: 0L) + 1L
            lastGpsFix = location
        }
    }

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("sensors")
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            startRequested = false
            handler.post {
                stopRecording()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (!startRequested) {
            startRequested = true
            // Must run promptly on this (main) thread after startForegroundService;
            // everything else is deferred to the handler thread.
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            handler.post { startRecording() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // quitSafely still delivers already-due messages, so this stop runs
        // (and any sensor events queued before it drain into the file first).
        handler.post { stopRecording() }
        handlerThread.quitSafely()
        super.onDestroy()
    }

    // ---- lifecycle (handler thread only) ---------------------------------

    private fun startRecording() {
        if (recording) return

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NavRecorder:sensors").apply {
            setReferenceCounted(false)
            acquire()
        }

        val file = openOutputFile()
        outputFile = file
        writer = BufferedWriter(FileWriter(file), WRITE_BUFFER_BYTES)

        eventCounts.clear()
        lastGpsFix = null
        bytesWritten = 0L
        startElapsedNs = SystemClock.elapsedRealtimeNanos()

        val registered = registerSensors()
        registerLocation()

        writeHeader(registered)

        recording = true
        handler.post(flushRunnable)
        handler.post(snapshotRunnable)
        postSnapshot()
    }

    private fun stopRecording() {
        if (!recording) {
            return
        }
        recording = false

        sensorManager?.unregisterListener(sensorEventListener)
        locationManager?.removeUpdates(locationListener)

        handler.removeCallbacks(flushRunnable)
        handler.removeCallbacks(snapshotRunnable)

        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "error closing output file", e)
        }
        writer = null

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        postSnapshot()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---- sensors / location ---------------------------------------------

    private fun registerSensors(): List<Sensor> {
        val manager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager = manager
        val registered = mutableListOf<Sensor>()
        for ((type, tag) in SENSOR_TAGS) {
            val sensor = manager.getDefaultSensor(type)
            if (sensor == null) {
                Log.w(TAG, "sensor unavailable, skipping: $tag")
                continue
            }
            val ok = manager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            if (ok) {
                registered.add(sensor)
            } else {
                Log.w(TAG, "failed to register sensor, skipping: $tag")
            }
        }
        return registered
    }

    private fun registerLocation() {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = manager
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                locationListener,
                handlerThread.looper,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission missing, GNSS logging disabled", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "GPS provider unavailable, GNSS logging disabled", e)
        }
    }

    // ---- file I/O --------------------------------------------------------

    private fun openOutputFile(): File {
        val dir = File(getExternalFilesDir(null), "recordings")
        dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "rec_$timestamp.csv")
    }

    private fun writeHeader(registeredSensors: List<Sensor>) {
        val w = writer ?: return
        val sb = StringBuilder()
        sb.append("# nav-recorder v1\n")
        sb.append("# device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" android=").append(Build.VERSION.RELEASE)
            .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
        sb.append("# session_start_utc_ms=").append(System.currentTimeMillis())
            .append(" session_start_elapsed_ns=").append(startElapsedNs).append('\n')
        for (sensor in registeredSensors) {
            val tag = SENSOR_TYPE_TO_TAG[sensor.type] ?: continue
            sb.append("# sensor tag=").append(tag)
                .append(" name=").append(sensor.name)
                .append(" vendor=").append(sensor.vendor)
                .append(" resolution=").append(sensor.resolution)
                .append(" maxRange=").append(sensor.maximumRange)
                .append(" minDelayUs=").append(sensor.minDelay)
                .append('\n')
        }
        sb.append("# columns imu: tag,timestamp_ns,accuracy,v0..vN (all values from event.values, N varies by sensor)\n")
        sb.append("# columns gps: gps,elapsed_ns,utc_ms,lat,lon,alt,speed_mps,bearing_deg,h_acc_m,v_acc_m,speed_acc_mps,bearing_acc_deg\n")
        w.write(sb.toString())
        bytesWritten += sb.length
    }

    private fun writeImuRow(tag: String, event: SensorEvent) {
        // Events already queued on the looper when stopRecording ran arrive
        // after the writer is closed — drop them.
        if (!recording) return
        val w = writer ?: return
        val sb = StringBuilder(80)
        sb.append(tag).append(',')
            .append(event.timestamp).append(',')
            .append(event.accuracy)
        for (v in event.values) {
            sb.append(',').append(v)
        }
        sb.append('\n')
        w.write(sb.toString())
        bytesWritten += sb.length
    }

    private fun writeGpsRow(location: Location) {
        if (!recording) return
        val w = writer ?: return
        val sb = StringBuilder(128)
        sb.append("gps").append(',')
            .append(location.elapsedRealtimeNanos).append(',')
            .append(location.time).append(',')
            .append(location.latitude).append(',')
            .append(location.longitude).append(',')
        if (location.hasAltitude()) sb.append(location.altitude)
        sb.append(',')
        if (location.hasSpeed()) sb.append(location.speed)
        sb.append(',')
        if (location.hasBearing()) sb.append(location.bearing)
        sb.append(',')
        if (location.hasAccuracy()) sb.append(location.accuracy)
        sb.append(',')
        if (location.hasVerticalAccuracy()) sb.append(location.verticalAccuracyMeters)
        sb.append(',')
        if (location.hasSpeedAccuracy()) sb.append(location.speedAccuracyMetersPerSecond)
        sb.append(',')
        if (location.hasBearingAccuracy()) sb.append(location.bearingAccuracyDegrees)
        sb.append('\n')
        w.write(sb.toString())
        bytesWritten += sb.length
    }

    // ---- UI state --------------------------------------------------------

    private fun postSnapshot() {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val elapsedSec = (nowNs - startElapsedNs).coerceAtLeast(1L) / 1_000_000_000.0
        val hz = LinkedHashMap<String, Double>()
        for ((tag, count) in eventCounts) {
            hz[tag] = count / elapsedSec
        }
        val gps = lastGpsFix?.let { loc ->
            LastGps(
                ageMs = (nowNs - loc.elapsedRealtimeNanos) / 1_000_000L,
                lat = loc.latitude,
                lon = loc.longitude,
                speedMps = if (loc.hasSpeed()) loc.speed else null,
                hAccM = if (loc.hasAccuracy()) loc.accuracy else null,
            )
        }
        RecorderState.update(
            Status(
                isRecording = recording,
                startElapsedNs = startElapsedNs,
                eventCounts = LinkedHashMap(eventCounts),
                hzByTag = hz,
                lastGps = gps,
                fileName = outputFile?.name ?: "",
                bytesWritten = bytesWritten,
            )
        )
    }

    // ---- notification ------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("Recording")
            .setDescription("Ongoing sensor recording session")
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nav Recorder")
            .setContentText("Recording sensors and GNSS")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }
}
