package com.example.minidash

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private const val SERVICE_UUID = "5b00a1b0-7c6f-4f81-9f89-916956b61234"
private const val CHAR_SPEED_UUID = "5b00a1b1-7c6f-4f81-9f89-916956b61234"
private const val CHAR_NOTIFICATION_UUID = "5b00a1b2-7c6f-4f81-9f89-916956b61234"
private const val CHAR_NAV_UUID = "5b00a1b3-7c6f-4f81-9f89-916956b61234"

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )

        BleController.initialize(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen()
                }
            }
        }
    }
}

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    var destination by remember { mutableStateOf("") }
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 12f)
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "MiniDash", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { BleController.startScan(context) }) { Text("Connect BLE") }
            Button(onClick = {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SpeedService::class.java)
                )
            }) { Text("Start speed") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it },
            label = { Text("Destination address") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                NavigationRepository.requestRoute(context, destination)
            })
        )
        TextButton(onClick = { NavigationRepository.requestRoute(context, destination) }) {
            Text("Fetch route + send")
        }
        Spacer(modifier = Modifier.height(8.dp))
        GoogleMap(cameraPositionState = cameraState, modifier = Modifier.weight(1f))
    }
}

object BleController {
    private const val TAG = "MiniDashBle"
    private var gatt: BluetoothGatt? = null
    private var speedChar: BluetoothGattCharacteristic? = null
    private var notifChar: BluetoothGattCharacteristic? = null
    private var navChar: BluetoothGattCharacteristic? = null
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var adapter: BluetoothAdapter

    fun initialize(context: Context) {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = bluetoothManager.adapter
    }

    fun startScan(context: Context) {
        if (!::adapter.isInitialized || adapter.bluetoothLeScanner == null) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        adapter.bluetoothLeScanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                if (device.name?.contains("MiniDash", ignoreCase = true) == true) {
                    adapter.bluetoothLeScanner.stopScan(this)
                    connect(context, device)
                }
            }
        })
    }

    fun connect(context: Context, device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service: BluetoothGattService? = gatt.getService(UUID.fromString(SERVICE_UUID))
                service?.let {
                    speedChar = it.getCharacteristic(UUID.fromString(CHAR_SPEED_UUID))
                    notifChar = it.getCharacteristic(UUID.fromString(CHAR_NOTIFICATION_UUID))
                    navChar = it.getCharacteristic(UUID.fromString(CHAR_NAV_UUID))
                }
            }
        })
    }

    fun sendSpeed(speedMps: Float, accuracyM: Float) {
        val kmh = speedMps * 3.6f
        val buffer = ByteBuffer.allocate(1 + 4 + 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x01)
        buffer.putFloat(kmh)
        buffer.putFloat(accuracyM)
        buffer.putInt((System.currentTimeMillis() % Int.MAX_VALUE).toInt())
        write(speedChar, buffer.array())
    }

    fun sendNotification(app: String, title: String, text: String) {
        val appBytes = app.toByteArray(Charsets.UTF_8).take(31).toByteArray()
        val titleBytes = title.toByteArray(Charsets.UTF_8).take(63).toByteArray()
        val textBytes = text.toByteArray(Charsets.UTF_8).take(63).toByteArray()
        val buffer = ByteBuffer.allocate(4 + appBytes.size + titleBytes.size + textBytes.size)
        buffer.put(0x02)
        buffer.put(appBytes.size.toByte())
        buffer.put(titleBytes.size.toByte())
        buffer.put(textBytes.size.toByte())
        buffer.put(appBytes)
        buffer.put(titleBytes)
        buffer.put(textBytes)
        write(notifChar, buffer.array())
    }

    fun sendNav(maneuver: Int, distanceM: Float, etaS: Int) {
        val buffer = ByteBuffer.allocate(1 + 1 + 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x03)
        buffer.put(maneuver.toByte())
        buffer.putFloat(distanceM)
        buffer.putInt(etaS)
        write(navChar, buffer.array())
    }

    private fun write(char: BluetoothGattCharacteristic?, data: ByteArray) {
        val g = gatt ?: return
        val c = char ?: return
        c.value = data
        g.writeCharacteristic(c)
    }
}

class SpeedService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 200L)
        .setMinUpdateIntervalMillis(200)
        .build()
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleLocation(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundNotification()
        requestUpdates()
    }

    private fun requestUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        fusedLocationClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
    }

    private fun handleLocation(location: Location) {
        val speed = if (location.hasSpeed()) location.speed else 0f
        val acc = if (location.hasAccuracy()) location.accuracy else 0f
        BleController.sendSpeed(speed, acc)
        NavigationRepository.onLocation(location)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val channelId = "speed_service"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Speed", NotificationManager.IMPORTANCE_LOW)
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MiniDash running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(1, notification)
    }
}

class MiniDashNotificationListener : android.service.notification.NotificationListenerService() {
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        BleController.sendNotification(sbn.packageName, title, text)
    }
}

object NavigationRepository {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private var steps: List<NavStep> = emptyList()
    private var currentIndex = 0
    private var lastLocation: Location? = null

    fun requestRoute(context: Context, destination: String) {
        val origin = lastLocation
        if (origin == null) {
            Log.w("Nav", "No origin location yet")
            return
        }
        val apiKey = context.getString(R.string.app_name) // Replace with local.properties mapping
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${destination}&mode=driving&key=${apiKey}"
        val request = Request.Builder().url(url).build()
        Thread {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: return@use
                val route = json.decodeFromString(DirectionsResponse.serializer(), body)
                steps = route.routes.firstOrNull()?.legs?.firstOrNull()?.steps ?: emptyList()
                currentIndex = 0
                sendNextStep(origin)
            }
        }.start()
    }

    fun onLocation(location: Location) {
        lastLocation = location
        sendNextStep(location)
    }

    private fun sendNextStep(location: Location) {
        if (steps.isEmpty()) return
        val current = steps[currentIndex]
        val remaining = distanceMeters(location, current.endLocation.lat, current.endLocation.lng)
        if (remaining < 25 && currentIndex < steps.lastIndex) {
            currentIndex++
        }
        val maneuverId = when (current.maneuver) {
            "turn-left" -> 2
            "turn-right" -> 3
            "uturn-left", "uturn-right" -> 4
            "roundabout-left", "roundabout-right" -> 5
            else -> 1
        }
        BleController.sendNav(maneuverId, remaining, current.duration.value)
    }

    private fun distanceMeters(location: Location, lat: Double, lng: Double): Float {
        val res = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, lat, lng, res)
        return res[0]
    }
}

@Serializable
data class DirectionsResponse(val routes: List<Route>)

@Serializable
data class Route(val legs: List<Leg>)

@Serializable
data class Leg(val steps: List<NavStep>)

@Serializable
data class NavStep(
    val end_location: Point,
    val duration: Duration,
    val maneuver: String? = null
) {
    val endLocation: Point get() = end_location
}

@Serializable
data class Point(val lat: Double, val lng: Double)

@Serializable
data class Duration(val value: Int)
