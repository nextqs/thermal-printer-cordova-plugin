# USB status checks

Run the bridge check with `node --test tests/bridge.test.js`.

Run the Android compile and mocked USB checks with `python3 tests/run-android-status-tests.py`.
This standalone runner requires JDK 17+, Python 3, Android SDK platform 33+ (`ANDROID_HOME`),
and an existing Gradle cache (`GRADLE_USER_HOME`, default `~/.gradle`). It does not download
dependencies or modify the application/fork. Required cached artifacts:

- ESC/POS `com.github.nextqs:ESCPOS-ThermalPrinter-Android:3.6.0` and Cordova framework `14.0.1` AARs.
- ESC/POS runtime dependency `com.google.zxing:core:3.4.0` JAR.
- Cordova's AndroidX dependencies and Kotlin standard library.
- `org.json:json:20250517`, `org.mockito:mockito-core:5.20.0`,
  Byte Buddy and Byte Buddy agent `1.17.7`, and Objenesis `3.3` JARs.

The production source is compiled against the real Android SDK. A test-only clock shim allows
Mockito to control Android's native clock on the JVM. Checks cover protocol framing, every possible
status byte, sensor pairs, raw bytes, bounded transfers, missing/partial responses, permission and
endpoint checks, connection cleanup, cache aliases, unrelated devices, absent devices and busy USB.
Print regression checks cover transport/rendering failures, cache replacements, connection setup,
and a valid print following an invalid payload. Failed status claims use distinct mock descriptors
to verify that every opened descriptor is closed exactly once.

These tests simulate the transport. Follow the model-specific physical validation table in
the main README before relying on the sensors in the kiosk.
