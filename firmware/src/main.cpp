#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <NimBLEDevice.h>
#include <deque>

// OLED wiring
static constexpr int OLED_WIDTH = 128;
static constexpr int OLED_HEIGHT = 64;
static constexpr int OLED_RESET = -1; // shared reset
static constexpr int OLED_SDA = 21;
static constexpr int OLED_SCL = 20;

Adafruit_SSD1306 display(OLED_WIDTH, OLED_HEIGHT, &Wire, OLED_RESET);

// BLE UUIDs
static const char *SERVICE_UUID = "5b00a1b0-7c6f-4f81-9f89-916956b61234";
static const char *CHAR_SPEED_UUID = "5b00a1b1-7c6f-4f81-9f89-916956b61234";
static const char *CHAR_NOTIFICATION_UUID = "5b00a1b2-7c6f-4f81-9f89-916956b61234";
static const char *CHAR_NAV_UUID = "5b00a1b3-7c6f-4f81-9f89-916956b61234";

// Packet types (byte 0)
enum PacketType : uint8_t { PACKET_SPEED = 0x01, PACKET_NOTIFICATION = 0x02, PACKET_NAV = 0x03 };

enum Maneuver : uint8_t {
  MANEUVER_UNKNOWN = 0,
  MANEUVER_STRAIGHT,
  MANEUVER_LEFT,
  MANEUVER_RIGHT,
  MANEUVER_UTURN,
  MANEUVER_ROUNDABOUT
};

struct SpeedSample {
  float speedKmh = 0.0f;
  float accuracyM = 0.0f;
  uint32_t timestamp = 0; // ms since boot
};

struct NotificationItem {
  String app;
  String title;
  String text;
  uint32_t receivedMs;
};

struct NavState {
  Maneuver maneuver = MANEUVER_UNKNOWN;
  float distanceM = 0.0f;
  uint32_t etaS = 0;
  bool active = false;
};

static std::deque<SpeedSample> speedWindow;
static const size_t SPEED_WINDOW_MAX = 5;
static std::deque<NotificationItem> notificationQueue;
static const size_t NOTIFICATION_MAX = 3;
static NavState navState;

static bool bleConnected = false;
static uint32_t lastSpeedUpdate = 0;
static uint32_t lastRender = 0;
static uint32_t notificationDisplayStart = 0;
static bool showingNotification = false;
static size_t notificationIndex = 0;

static NimBLECharacteristic *speedChar;
static NimBLECharacteristic *notificationChar;
static NimBLECharacteristic *navChar;

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer *pServer) override {
    bleConnected = true;
  }
  void onDisconnect(NimBLEServer *pServer) override {
    bleConnected = false;
    NimBLEDevice::startAdvertising();
  }
};

static void addSpeedSample(float speedKmh, float accuracyM) {
  SpeedSample s{speedKmh, accuracyM, (uint32_t)millis()};
  speedWindow.push_back(s);
  if (speedWindow.size() > SPEED_WINDOW_MAX) {
    speedWindow.pop_front();
  }
  lastSpeedUpdate = millis();
}

static float getSmoothedSpeed() {
  if (speedWindow.empty()) return 0.0f;
  float sum = 0.0f;
  for (const auto &s : speedWindow) sum += s.speedKmh;
  return sum / speedWindow.size();
}

static float latestAccuracy() {
  if (speedWindow.empty()) return 0.0f;
  return speedWindow.back().accuracyM;
}

static void enqueueNotification(const String &app, const String &title, const String &text) {
  NotificationItem n{app, title, text, (uint32_t)millis()};
  if (notificationQueue.size() >= NOTIFICATION_MAX) {
    notificationQueue.pop_front();
  }
  notificationQueue.push_back(n);
  notificationIndex = notificationQueue.size() - 1;
  showingNotification = true;
  notificationDisplayStart = millis();
}

static void updateNav(Maneuver m, float distanceM, uint32_t etaS) {
  navState.maneuver = m;
  navState.distanceM = distanceM;
  navState.etaS = etaS;
  navState.active = m != MANEUVER_UNKNOWN;
}

static void drawConnectionStatus(int16_t y) {
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, y);
  display.print(bleConnected ? "BLE:Connected" : "BLE:Disc");
  if (millis() - lastSpeedUpdate > 2000) {
    display.print(" | waiting for data");
  }
}

static void drawSpeedLarge() {
  display.setTextSize(3);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 12);
  char buff[10];
  snprintf(buff, sizeof(buff), "%3.0f", getSmoothedSpeed());
  display.print(buff);
  display.setTextSize(1);
  display.setCursor(90, 12);
  display.println("km/h");
  display.setCursor(0, 42);
  display.print("Acc: ");
  display.print(latestAccuracy(), 0);
  display.print("m");
}

static void drawNavSmall() {
  display.setTextSize(1);
  display.setCursor(80, 42);
  if (!navState.active) {
    display.print("No nav");
    return;
  }
  display.print(navState.distanceM, 0);
  display.print("m");
  display.setCursor(80, 52);
  switch (navState.maneuver) {
    case MANEUVER_LEFT:
      display.print("Turn L");
      break;
    case MANEUVER_RIGHT:
      display.print("Turn R");
      break;
    case MANEUVER_STRAIGHT:
      display.print("Straight");
      break;
    case MANEUVER_UTURN:
      display.print("U-turn");
      break;
    case MANEUVER_ROUNDABOUT:
      display.print("Roundabt");
      break;
    default:
      display.print("Nav?");
      break;
  }
}

static void drawManeuverArrow(Maneuver m) {
  int16_t cx = 64;
  int16_t cy = 32;
  switch (m) {
    case MANEUVER_LEFT:
      display.drawTriangle(cx - 20, cy, cx, cy - 10, cx, cy + 10, SSD1306_WHITE);
      display.drawLine(cx, cy - 10, cx + 20, cy - 10, SSD1306_WHITE);
      display.drawLine(cx, cy + 10, cx + 20, cy + 10, SSD1306_WHITE);
      break;
    case MANEUVER_RIGHT:
      display.drawTriangle(cx + 20, cy, cx, cy - 10, cx, cy + 10, SSD1306_WHITE);
      display.drawLine(cx, cy - 10, cx - 20, cy - 10, SSD1306_WHITE);
      display.drawLine(cx, cy + 10, cx - 20, cy + 10, SSD1306_WHITE);
      break;
    case MANEUVER_STRAIGHT:
      display.drawTriangle(cx, cy - 20, cx - 10, cy, cx + 10, cy, SSD1306_WHITE);
      display.drawLine(cx - 10, cy, cx - 10, cy + 20, SSD1306_WHITE);
      display.drawLine(cx + 10, cy, cx + 10, cy + 20, SSD1306_WHITE);
      break;
    case MANEUVER_UTURN:
      display.drawCircle(cx, cy - 5, 12, SSD1306_WHITE);
      display.drawLine(cx, cy - 17, cx, cy + 20, SSD1306_WHITE);
      display.drawTriangle(cx, cy + 20, cx - 8, cy + 10, cx + 8, cy + 10, SSD1306_WHITE);
      break;
    case MANEUVER_ROUNDABOUT:
      display.drawCircle(cx, cy, 14, SSD1306_WHITE);
      display.drawTriangle(cx + 12, cy, cx + 2, cy - 8, cx + 2, cy + 8, SSD1306_WHITE);
      break;
    default:
      display.drawRect(cx - 6, cy - 6, 12, 12, SSD1306_WHITE);
      break;
  }
}

static void renderMain() {
  display.clearDisplay();
  drawSpeedLarge();
  drawNavSmall();
  drawConnectionStatus(54);
  display.display();
}

static void renderNotificationOverlay() {
  if (notificationQueue.empty()) {
    showingNotification = false;
    return;
  }
  const NotificationItem &n = notificationQueue[notificationIndex % notificationQueue.size()];
  display.clearDisplay();
  display.fillRect(0, 0, OLED_WIDTH, OLED_HEIGHT, SSD1306_BLACK);
  display.fillRect(0, 0, OLED_WIDTH, 12, SSD1306_WHITE);
  display.setTextColor(SSD1306_BLACK);
  display.setTextSize(1);
  display.setCursor(2, 2);
  display.print(n.app.substring(0, 16));
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 16);
  display.print(n.title.substring(0, 20));
  display.setCursor(0, 28);
  display.print(n.text.substring(0, 20));
  display.display();
}

static void renderNavFocus() {
  display.clearDisplay();
  drawManeuverArrow(navState.maneuver);
  display.setTextSize(2);
  display.setCursor(0, 48);
  display.print(navState.distanceM, 0);
  display.print(" m");
  display.display();
}

class DataCharacteristicCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *pCharacteristic) override {
    std::string value = pCharacteristic->getValue();
    if (value.empty()) return;
    const uint8_t *data = reinterpret_cast<const uint8_t *>(value.data());
    size_t len = value.length();
    PacketType type = static_cast<PacketType>(data[0]);

    switch (type) {
      case PACKET_SPEED: {
        if (len < 1 + sizeof(float) * 2 + sizeof(uint32_t)) return;
        float speedKmh, accuracyM;
        uint32_t timestamp;
        memcpy(&speedKmh, data + 1, sizeof(float));
        memcpy(&accuracyM, data + 1 + sizeof(float), sizeof(float));
        memcpy(&timestamp, data + 1 + sizeof(float) * 2, sizeof(uint32_t));
        addSpeedSample(speedKmh, accuracyM);
        lastSpeedUpdate = millis();
        break;
      }
      case PACKET_NOTIFICATION: {
        if (len < 4) return;
        uint8_t appLen = data[1];
        uint8_t titleLen = data[2];
        uint8_t textLen = data[3];
        if (4 + appLen + titleLen + textLen > len) return;
        String app = String((const char *)(data + 4)).substring(0, appLen);
        String title = String((const char *)(data + 4 + appLen)).substring(0, titleLen);
        String text = String((const char *)(data + 4 + appLen + titleLen)).substring(0, textLen);
        enqueueNotification(app, title, text);
        break;
      }
      case PACKET_NAV: {
        if (len < 1 + 1 + sizeof(float) + sizeof(uint32_t)) return;
        Maneuver m = static_cast<Maneuver>(data[1]);
        float distanceM;
        uint32_t etaS;
        memcpy(&distanceM, data + 2, sizeof(float));
        memcpy(&etaS, data + 2 + sizeof(float), sizeof(uint32_t));
        updateNav(m, distanceM, etaS);
        break;
      }
      default:
        break;
    }
  }
};

void setup() {
  Serial.begin(115200);
  Wire.begin(OLED_SDA, OLED_SCL);
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    Serial.println("SSD1306 allocation failed");
    for (;;) delay(100);
  }
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0);
  display.println("Mini Dashboard");
  display.println("Waiting for BLE...");
  display.display();

  NimBLEDevice::init("MiniDash ESP32");
  NimBLEDevice::setMTU(185);
  NimBLEServer *server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  NimBLEService *service = server->createService(SERVICE_UUID);
  speedChar = service->createCharacteristic(CHAR_SPEED_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
  notificationChar = service->createCharacteristic(CHAR_NOTIFICATION_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
  navChar = service->createCharacteristic(CHAR_NAV_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);

  auto *callbacks = new DataCharacteristicCallbacks();
  speedChar->setCallbacks(callbacks);
  notificationChar->setCallbacks(callbacks);
  navChar->setCallbacks(callbacks);

  service->start();
  NimBLEAdvertising *advertising = NimBLEDevice::getAdvertising();
  advertising->addServiceUUID(service->getUUID());
  advertising->setScanResponse(true);
  advertising->start();
}

void loop() {
  const uint32_t now = millis();

  // Auto-hide notification overlay after 8 seconds.
  if (showingNotification && now - notificationDisplayStart > 8000) {
    showingNotification = false;
  }

  // Auto-rotate notifications every 10 seconds when idle.
  if (!notificationQueue.empty() && !showingNotification && now - notificationDisplayStart > 10000) {
    notificationIndex = (notificationIndex + 1) % notificationQueue.size();
    showingNotification = true;
    notificationDisplayStart = now;
  }

  // Refresh display at ~10 FPS.
  if (now - lastRender > 100) {
    lastRender = now;
    if (showingNotification) {
      renderNotificationOverlay();
    } else if (navState.active && navState.distanceM < 150.0f) {
      renderNavFocus();
    } else {
      renderMain();
    }
  }

  delay(10);
}
