# Mini Dashboard System

An end-to-end ESP32-C3 + Android dashboard that mirrors phone speed, notifications, and navigation cues onto a 0.96" I2C OLED.

## 1) Architecture summary
- **ESP32-C3 Mini** acts as BLE peripheral + OLED renderer (SSD1306 over I2C on SDA=GPIO21, SCL=GPIO20). It accepts writes on three BLE characteristics for speed, notifications, and navigation, smooths speed, and renders three modes (main, notification overlay, nav focus).
- **Android app** acts as BLE central. It reads GPS speed (Fused Location), listens to notifications (NotificationListenerService), fetches turn-by-turn steps via Google Directions API, and writes compact binary packets to the ESP32 characteristics. UI provides map, destination entry, and BLE connect/start controls.

## 2) BLE UUIDs + payload format
Custom service UUID `5b00a1b0-7c6f-4f81-9f89-916956b61234` with three characteristics (write + notify):

| Characteristic | UUID | Packet type (byte0) | Payload |
| --- | --- | --- | --- |
| SPEED | `5b00a1b1-7c6f-4f81-9f89-916956b61234` | `0x01` | `[type][float speed_kmh][float accuracy_m][uint32 timestamp_ms]` (little endian) |
| NOTIFICATION | `5b00a1b2-7c6f-4f81-9f89-916956b61234` | `0x02` | `[type][u8 appLen][u8 titleLen][u8 textLen][app][title][text]` UTF-8 truncated to fit MTU |
| NAV | `5b00a1b3-7c6f-4f81-9f89-916956b61234` | `0x03` | `[type][u8 maneuver][float distance_m][uint32 eta_s]` |

Maneuver enums on ESP32: 0=UNKNOWN,1=STRAIGHT,2=LEFT,3=RIGHT,4=UTURN,5=ROUNDABOUT.

## 3) ESP32 firmware (PlatformIO/Arduino)
Location: `firmware/`.

- Build: `cd firmware && pio run -t upload` (board `esp32-c3-devkitm-1`, USB CDC on boot). Libraries: NimBLE-Arduino, Adafruit_SSD1306, Adafruit_GFX.
- I2C pins: SDA=GPIO21, SCL=GPIO20, VDD=3V3, GND=GND.
- UI: main screen (large smoothed speed + accuracy + nav teaser + BLE state), notification banner (auto-hide after 8s, auto-rotate queue of 3), nav focus (arrow + distance when <150 m to turn).
- BLE: always advertises when disconnected; shared callbacks parse packets and update UI state.

Key file: `firmware/src/main.cpp`.

## 4) Android app (Kotlin, Compose)
Location: `android-app/` (Android Studio project).

- **Permissions**: fine/coarse location, background location (optional), Bluetooth scan/connect, foreground service, POST_NOTIFICATIONS, notification listener.
- **BLE central**: `BleController` scans for "MiniDash" devices, connects, discovers custom service, and writes packets for speed/notification/nav.
- **Speed**: `SpeedService` foreground service uses `FusedLocationProviderClient` (200 ms interval) -> `BleController.sendSpeed()`.
- **Notifications**: `MiniDashNotificationListener` forwards title/text/app to ESP32.
- **Navigation**: `NavigationRepository` calls Google Directions API (provide your API key), parses steps, computes next maneuver + distance, sends `sendNav` updates as location changes.
- **UI**: `MainActivity` with Compose map, destination entry, BLE connect + start buttons. Uses Google Maps Compose.

### Setup
1. Open `android-app` in Android Studio (Arctic Fox+). Ensure `local.properties` contains your Google Maps/Directions API key (replace `context.getString(R.string.app_name)` in `NavigationRepository` with `BuildConfig.MAPS_API_KEY` from a gradle property if desired).
2. Enable Notification Listener permission in Android settings for the app.
3. Pair BLE by tapping **Connect BLE**, then start the foreground speed service.
4. Enter a destination and tap **Fetch route + send**; keep Google Maps running separately for full map UI if desired.

## 5) Testing checklist & troubleshooting
- **Firmware build/flash**: `cd firmware && pio run -t upload` (confirm USB CDC serial, board in bootloader). If OLED stays blank, verify 0x3C I2C address and SDA/SCL pins (21/20) plus 3V3 supply.
- **BLE link**: Confirm ESP32 advertises `MiniDash ESP32`. On Android, ensure location + Bluetooth permissions granted; if connect fails, reboot BLE on ESP32 or toggle airplane mode on phone.
- **Speed updates**: Move with phone; watch serial logs for speed packets; OLED speed should smooth within ~5 samples.
- **Notifications**: Enable listener in settings; send a test notification; banner should appear for 8s and auto-rotate through last 3.
- **Navigation**: Verify Directions API key; check logcat for "Nav" tag errors; ensure location has a fix before requesting route.
