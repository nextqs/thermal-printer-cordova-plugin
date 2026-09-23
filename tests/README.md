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

The production source is compiled against the real Android SDK. Test-only clock and receiver shims
allow Mockito to control Android's clock and invoke permission callbacks on the JVM. Checks cover
protocol framing, every possible status byte, sensor pairs, raw bytes, bounded transfers, missing/partial responses, permission and
endpoint checks, connection cleanup, cache aliases, unrelated devices, absent devices and busy USB.
Primary-source checks exercise `GET_PORT_STATUS` with bench bytes `0x18` (ready), `0x10` (stopped
with paper) and `0x30` (stopped without paper). They verify request fields, bounded timeout, raw
evidence, unknown cover/near-end sensors, skipped DLE EOT for stopped printers, and fallback after
failed control transfers. The ready case also runs with no DLE EOT reply so it cannot hide an
incorrect class paper bit behind a secondary response.
Print regression checks cover transport/rendering failures, cache replacements, connection setup,
and a valid print following an invalid payload. Failed status claims use distinct mock descriptors
to verify that every opened descriptor is closed exactly once.
USB action checks verify bounded waits before encoding/image writer construction, status exclusion,
permission callbacks without opening/caching a writer, and kiosk recovery using only `type`/`id`
while a native-operation lock is held. The two timeout checks take about ten seconds in total.
Detach checks call the production filter directly: an unrelated device (including one with a reused
numeric ID) preserves both printers, while detaching the selected enumeration removes only its aliases.

Pull requests and pushes to `main` or `sandbox` run both commands in `.github/workflows/usb-status-tests.yml`.
The workflow installs Android platform 35 and resolves the fixed test dependencies declared in
`tests/dependencies.gradle`; the Python runner still refuses missing dependencies and never downloads
anything by itself.

These tests simulate the transport. Follow the model-specific physical validation table in
the main README before relying on the sensors in the kiosk.
