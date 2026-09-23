## Cordova Plugin for Thermal Printer's

[![npm version](https://img.shields.io/npm/v/nxtqs-thermal-printer-cordova-plugin.svg)](https://www.npmjs.com/package/nxtqs-thermal-printer-cordova-plugin) [![npm downloads](https://img.shields.io/npm/dm/nxtqs-thermal-printer-cordova-plugin.svg)](https://www.npmjs.com/package/nxtqs-thermal-printer-cordova-plugin)

---

This plugin is a wrapper for the [Android library for ESC/POS Thermal Printer](https://github.com/DantSu/ESCPOS-ThermalPrinter-Android).

### Install

#### Cordova

    $ cordova plugin add nxtqs-thermal-printer-cordova-plugin

#### Ionic

    $ ionic cordova plugin add nxtqs-thermal-printer-cordova-plugin

#### Capacitor

    $ npm install nxtqs-thermal-printer-cordova-plugin
    $ npx cap sync

Don't forget to add BLUETOOTH and INTERNET (for TCP) permissions and for USB printers the `android.hardware.usb.host` feature to the `AndroidManifest.xml`.

```xml
<uses-feature android:name="android.hardware.usb.host" />
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.INTERNET" />
```

### Examples

#### Notice for TypeScript-Developers

You can easily import and use the ThermalPrinter plugin in your TypeScript-Projects.

```typescript
import { ThermalPrinterPlugin } from "nxtqs-thermal-printer-cordova-plugin/src";

declare let ThermalPrinter: ThermalPrinterPlugin;
```

And then use the following examples in your code.

#### Print via Internal Urovo/Gertec Printer (GPOS820)

**Available since v1.1.0** — This printer type uses structured operations (text, QR codes, images, gaps) instead of formatted text strings. It communicates directly with the device's internal PrinterManager API.

**Prerequisites:**

- Device must have `android.device.PrinterManager` available (typically Urovo/Gertec devices with GPOS820 or similar printers)
- Permissions in `AndroidManifest.xml`:
  ```xml
  <uses-permission android:name="smartpos.deviceservice.permission.Printer" />
  <uses-permission android:name="android.permission.CLOUDPOS_PRINTER" />
  ```

**Basic Usage:**

```javascript
ThermalPrinter.listPrinters(
  { type: "internal-urovo" },
  function (printers) {
    if (printers.length > 0) {
      var printer = printers[0];
      ThermalPrinter.printInternalUrovoPage(
        {
          type: "internal-urovo",
          id: printer.id,
          operations: [
            {
              type: "text",
              value: "Hello World!",
              size: "normal",
              align: "center",
            },
            {
              type: "qr",
              value: "https://example.com",
              size: 200,
            },
            {
              type: "gap",
              value: 20, // millimeters
            },
          ],
        },
        function () {
          console.log("Successfully printed!");
        },
        function (error) {
          console.error("Printing error", error);
        },
      );
    }
  },
  function (error) {
    console.error("No printers found", error);
  },
);
```

**Operation Types:**

- **text**: Print text with specified size and alignment
  - `value`: string (text to print)
  - `size`: 'small' | 'normal' | 'title' | 'ticket'
  - `align`: 'left' | 'center' | 'right'
  - `x`: number (optional, horizontal offset in pixels)
  - `topGap`: number (optional, gap before text in pixels)

- **qr**: Print QR code
  - `value`: string (URL or data to encode)
  - `size`: number (QR code size in pixels)
  - `x`: number (optional, horizontal offset)
  - `topGap`: number (optional, gap before QR in pixels)

- **image**: Print image
  - `value`: string (base64 encoded image)
  - `x`: number (optional, horizontal offset)
  - `topGap`: number (optional, gap before image in pixels)

- **gap**: Add vertical spacing
  - `value`: number (gap size in pixels)

#### Print via Bluetooth

Printing via Bluetooth is as easy as possible.

```javascript
ThermalPrinter.printFormattedText({
    type: 'bluetooth',
    id: 'first', // You can also use the identifier directly i. e. 00:11:22:33:44:55 (address) or name
    text: '[C]<u><font size='big'>Hello World</font></u>' // new lines with "\n"
}, function() {
    console.log('Successfully printed!');
}, function(error) {
    console.error('Printing error', error);
});
```

**Notice:** If not working please ensure that you have the printer connected. (Settings -> Bluetooth -> Pairing)
If you have other issues maybe you have not granted the `android.permission.BLUETOOTH` permission.

#### Print via TCP

Printing via TCP is as easy as possible.

```javascript
ThermalPrinter.printFormattedText({
    type: 'tcp',
    address: '192.168.1.123',
    port: 9100,
    id: 'tcp-printer-001', // Use an unique identifier for each printer i. e. address:port or name
    text: '[C]<u><font size='big'>Hello World</font></u>' // new lines with "\n"
}, function() {
    console.log('Successfully printed!');
}, function(error) {
    console.error('Printing error', error);
});
```

**Notice:** If not working please ensure that your device can ping the printer. And the printer must be a POSPrinter!
Also ensure that you're using the correct port. 9100 is default for the thermal printers.

#### Print via USB (incl. listPrinters and requestPermissions)

1. First we get our printer because we don't know the printer's ID.
2. Then we request permissions for printing. This is needed because Android will not allow us to access all devices.
3. And finally we can print with our device.

```javascript
ThermalPrinter.listPrinters({type: 'usb'}, function(printers) {
    if (printers.length > 0) {
        var printer = printers[0];
        ThermalPrinter.requestPermissions(printer, function() {
            // Permission granted - We can print!
            ThermalPrinter.printFormattedText({
                type: 'usb',
                id: printer.id,
                text: '[C]<u><font size='big'>Hello World</font></u>' // new lines with "\n"
            }, function() {
                console.log('Successfully printed!');
            }, function(error) {
                console.error('Printing error', error);
            });
        }, function(error) {
            console.error('Permission denied - We can\'t print!');
        });
    } else {
        console.error('No printers found!');
    }
}, function(error) {
    console.error('Ups, we cant list the printers!', error);
});
```

**Reconnection handling (since v1.2.0):** USB connections are cached, and the cache is keyed by
`vendorId`/`productId`/`serialNumber`, which do not change when a device re-enumerates. The plugin
therefore also checks the `deviceId` of the cached device against the bus before reusing a connection,
because `deviceId` does change on re-enumeration and the open file descriptor dies with the old one.
Reusing a stale connection would fail with `Error during claim USB interface`. The cache is additionally
dropped when the device is detached, when a device comes back re-enumerated, and after a failed USB print.
This matters on OTG adapters that let the printer leave the bus — see [onUsbEvent](#onUsbEvent) to react
to it from the app.

### listPrinters(data, successCallback, errorCallback)

List available printers

| Param           | Type                                                               | Description                    |
| --------------- | ------------------------------------------------------------------ | ------------------------------ |
| data            | <code>Object</code>                                                | Data object                    |
| data.type       | <code>&quot;bluetooth&quot;</code> \| <code>&quot;usb&quot;</code> | Type of list: bluetooth or usb |
| successCallback | <code>function</code>                                              | Result on success              |
| errorCallback   | <code>function</code>                                              | Result on failure              |

<a name="printFormattedText"></a>

### printFormattedText(data, successCallback, errorCallback)

Print a formatted text and feed paper

**See**: https://github.com/DantSu/ESCPOS-ThermalPrinter-Android#formatted-text--syntax-guide

| Param                | Type                                                                                               | Description                                                                                |
| -------------------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| data                 | <code>Array.&lt;Object&gt;</code>                                                                  | Data object                                                                                |
| data.type            | <code>&quot;bluetooth&quot;</code> \| <code>&quot;tcp&quot;</code> \| <code>&quot;usb&quot;</code> | List all bluetooth or usb printers                                                         |
| [data.id]            | <code>string</code> \| <code>number</code>                                                         | ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId) |
| [data.address]       | <code>string</code>                                                                                | If type is "tcp" then the IP Address of the printer                                        |
| [data.port]          | <code>number</code>                                                                                | If type is "tcp" then the Port of the printer                                              |
| [data.mmFeedPaper]   | <code>number</code>                                                                                | Millimeter distance feed paper at the end                                                  |
| [data.dotsFeedPaper] | <code>number</code>                                                                                | Distance feed paper at the end                                                             |
| data.text            | <code>string</code>                                                                                | Formatted text to be printed                                                               |
| successCallback      | <code>function</code>                                                                              | Result on success                                                                          |
| errorCallback        | <code>function</code>                                                                              | Result on failure                                                                          |

<a name="printFormattedTextAndCut"></a>

### printFormattedTextAndCut(data, successCallback, errorCallback)

Print a formatted text, feed paper and cut the paper

**See**: https://github.com/DantSu/ESCPOS-ThermalPrinter-Android#formatted-text--syntax-guide

| Param                | Type                                                                                               | Description                                                                                |
| -------------------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| data                 | <code>Array.&lt;Object&gt;</code>                                                                  | Data object                                                                                |
| data.type            | <code>&quot;bluetooth&quot;</code> \| <code>&quot;tcp&quot;</code> \| <code>&quot;usb&quot;</code> | List all bluetooth or usb printers                                                         |
| [data.id]            | <code>string</code> \| <code>number</code>                                                         | ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId) |
| [data.address]       | <code>string</code>                                                                                | If type is "tcp" then the IP Address of the printer                                        |
| [data.port]          | <code>number</code>                                                                                | If type is "tcp" then the Port of the printer                                              |
| [data.mmFeedPaper]   | <code>number</code>                                                                                | Millimeter distance feed paper at the end                                                  |
| [data.dotsFeedPaper] | <code>number</code>                                                                                | Distance feed paper at the end                                                             |
| data.text            | <code>string</code>                                                                                | Formatted text to be printed                                                               |
| successCallback      | <code>function</code>                                                                              | Result on success                                                                          |
| errorCallback        | <code>function</code>                                                                              | Result on failure                                                                          |

<a name="getEncoding"></a>

### getEncoding(data, successCallback, errorCallback)

Get the printer encoding when available

| Param           | Type                                                                                               | Description                                                                                |
| --------------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| data            | <code>Array.&lt;Object&gt;</code>                                                                  | Data object                                                                                |
| data.type       | <code>&quot;bluetooth&quot;</code> \| <code>&quot;tcp&quot;</code> \| <code>&quot;usb&quot;</code> | List all bluetooth or usb printers                                                         |
| [data.id]       | <code>string</code> \| <code>number</code>                                                         | ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId) |
| [data.address]  | <code>string</code>                                                                                | If type is "tcp" then the IP Address of the printer                                        |
| [data.port]     | <code>number</code>                                                                                | If type is "tcp" then the Port of the printer                                              |
| successCallback | <code>function</code>                                                                              | Result on success                                                                          |
| errorCallback   | <code>function</code>                                                                              | Result on failure                                                                          |

<a name="disconnectPrinter"></a>

### disconnectPrinter(data, successCallback, errorCallback)

Close the connection with the printer

For USB, this closes existing cached connections only; it never opens a new connection. It remains
available while a print is blocked, and succeeds if there is nothing left to close. A `type`/`id`
selector also finds a connection cached under stable USB identifiers. Other printers are preserved.

| Param           | Type                                                                                               | Description                                                                                |
| --------------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| data            | <code>Array.&lt;Object&gt;</code>                                                                  | Data object                                                                                |
| data.type       | <code>&quot;bluetooth&quot;</code> \| <code>&quot;tcp&quot;</code> \| <code>&quot;usb&quot;</code> | List all bluetooth or usb printers                                                         |
| [data.id]       | <code>string</code> \| <code>number</code>                                                         | ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId) |
| [data.address]  | <code>string</code>                                                                                | If type is "tcp" then the IP Address of the printer                                        |
| [data.port]     | <code>number</code>                                                                                | If type is "tcp" then the Port of the printer                                              |
| successCallback | <code>function</code>                                                                              | Result on success                                                                          |
| errorCallback   | <code>function</code>                                                                              | Result on failure                                                                          |

<a name="requestPermissions"></a>

### requestPermissions(data, successCallback, errorCallback)

Request permissions for USB printers

| Param           | Type                                                                                               | Description                                                                                |
| --------------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| data            | <code>Array.&lt;Object&gt;</code>                                                                  | Data object                                                                                |
| data.type       | <code>&quot;bluetooth&quot;</code> \| <code>&quot;tcp&quot;</code> \| <code>&quot;usb&quot;</code> | List all bluetooth or usb printers                                                         |
| [data.id]       | <code>string</code> \| <code>number</code>                                                         | ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId) |
| [data.address]  | <code>string</code>                                                                                | If type is "tcp" then the IP Address of the printer                                        |
| [data.port]     | <code>number</code>                                                                                | If type is "tcp" then the Port of the printer                                              |
| successCallback | <code>function</code>                                                                              | Result on success                                                                          |
| errorCallback   | <code>function</code>                                                                              | Result on failure                                                                          |

<a name="getUsbDiagnostics"></a>

### getPrinterStatus(data, successCallback, errorCallback)

**Experimental, since v1.2.0 — USB only.** Queries the USB printer class `GET_PORT_STATUS`
(paper), then ESC/POS `DLE EOT 4` (paper, near end) and `DLE EOT 2` (cover). Pass the same USB
selector as printing (`type`, `id`, optional `vendorId`, `productId`, `serialNumber`); request
USB permission beforehand.

```javascript
ThermalPrinter.getPrinterStatus(
  { type: 'usb', id: printer.deviceId, vendorId: printer.vendorId, productId: printer.productId },
  function (status) {
    console.log('USB printer status:', JSON.stringify(status));
    if (status.paperPresent === false) console.log('Paper is absent');
    if (status.coverOpen === true) console.log('Cover is open');
    // null means unknown. Never block ticket issuance because a query did not answer.
  },
  function (error) { console.error('Status request failed:', error); }
);
```

| Result | Meaning |
| --- | --- |
| `paperPresent` | `true` / `false` / `null` (unknown) |
| `paperNearEnd` | `true` / `false` / `null`; requires the corresponding sensor |
| `coverOpen` | `true` / `false` / `null`; requires the model to implement the standard cover bit |
| `printerStopped` | `true` / `false` / `null`; class error bit. `true` means the printer stopped without naming a cause, so treat it as blocking and do not report a specific cause to the operator |
| `supported` | `true`: at least one valid reply; `false`: unsupported transport or missing bulk endpoints; `null`: undetermined |
| `raw` | `{ paper: number[], offline: number[], port: number[] }`, unsigned response bytes, including invalid replies; `[]` when none read |
| `reason` | `null` for a complete standard reply; otherwise one of the reasons below |

Reasons: `busy`, `device_not_found`, `permission_required`, `unsupported_transport`,
`no_status_endpoint`, `interface_unavailable`, `input_not_quiet`, `timeout`, `write_timeout`,
`invalid_response`, `io_error`, `partial`. These are **successful API responses**, not print errors.
`partial` means the class request answered but `DLE EOT` did not, so paper is known while cover and
near end are `null`. `write_timeout` means the printer stopped draining its bulk OUT pipe: its
receive buffer is full, which is itself a sign that it is offline.
Malformed arguments use the error callback. Partial results are possible: paper may be known
while cover is `null`. A timeout cannot establish whether the printer supports the command.

Printing, `getEncoding` and `bitmapToHexadecimalString` are serialized against status queries because
they open USB writers. They wait at most 5000 ms for the lock, then use the error callback with
`{ error: "USB printer is busy", type: "PRINT_ERROR" }`. Discovery, permissions and diagnostics do not
open writers. USB disconnect only closes cached connections and stays outside the lock so recovery
can close a blocked native write. Permission requests do not create cache entries.
A query returns `busy` immediately if a writer action holds the lock or is waiting.
Otherwise it releases cached printing connections for
the selected device, opens a temporary connection, claims the same printer interface with
`force=false`, retrying and forcing only on the last attempt, and always closes it before allowing
another operation. The next print reconnects
normally. This keeps library version 3.6.0 and avoids access to its private fields. A print arriving
during the query can wait for its bounded USB transfers: 400 ms per query, with a 1500 ms total
budget (Android open/claim/close and scheduling overhead are additional). A reply is polled every
5 ms, because an IN transfer with nothing pending returns 0 at once rather than waiting out its
timeout. A model that accepts `DLE EOT` and never answers is remembered by vendor/product and
skipped on later queries, so it costs the read budget only once. Avoid tight polling.
The existing detach, re-enumeration and print-failure recovery remains in place.

Old input is drained with a bounded loop. Replies must contain exactly one byte matching
`0xx1xx10b`; **bit 4 is 1**, as specified in the
[Epson DLE EOT reference](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/dle_eot.html).
Paper uses masks `0x60` and `0x0C`; mixed sensor bit pairs remain unknown. Cover uses `0x04`
from the offline response. After a missing/invalid paper response, the cover command is skipped
to avoid interpreting a late paper reply as cover status. Responses have no command identifiers;
model-specific delayed/unsolicited data still requires bench validation.

**Validated models.** EPSON TM-T20X (vendor `1208`, product `3623`) on Samsung SM-X236B, 2026-09-22.

`GET_PORT_STATUS` answers in under 2 ms in every state. Measured bytes: `0x18` ready, `0x10` cover
open with paper loaded, `0x30` out of paper with the cover closed. Bit 3 (Not Error) separates ready
from stopped in all three, which is what `printerStopped` reports; bit 5 separates out-of-paper from
the other causes.

`DLE EOT` works on this model **only while it is ready**, where it replies `0x12` to both commands in
about 6 ms and fills `coverOpen` and `paperNearEnd` with `reason: null`. The moment the printer stops
it goes silent, contrary to the ESC/POS promise that real-time commands are answered offline. So the
fields that would name the cause disappear exactly when a cause exists: in practice **cover open
surfaces as `printerStopped`, not as `coverOpen`**, and the class byte is the only source that
survives a fault. Once the receive buffer fills, the `DLE EOT` write itself fails (`write_timeout`)
while the class request keeps answering.

Because of this, silence is only taken as proof that a model never answers `DLE EOT` when it comes
from a printer reporting ready; silence from a stopped printer says nothing about the model and must
not disable the command. A valid reply does not prove that every sensor exists.

**`paperNearEnd` is unusable on this model.** With a roll holding exactly one more ticket, the paper
byte still read `0x12` — near-end bits clear, "paper adequate" — and one second after that ticket
printed the class byte went straight to `0x30`, out of paper. The model reports no intermediate state,
so the field never warns in time to change the roll, which is its only purpose. Treat it as absent
here and do not read `paperNearEnd: false` as evidence that a near-end sensor exists.

Note for bench work: the red stripe printed near the core of a thermal roll is a visual cue for the
operator. Nothing in the paper path can read it, and it has no relation to `paperNearEnd`, which
measures the remaining roll diameter through a separate sensor when the model has one.
Do not assume `paperNearEnd: false` proves a near-end sensor is installed. A snapshot is not
confirmation that a ticket printed, and some models retain the prior paper state while the cover
is open. Bluetooth, TCP and internal Urovo/Gertec status are outside this API's scope.

#### USB status bench validation

Install this `sandbox` checkout in the test application, sync/rebuild Android, and call the API
after USB permission is granted. For every model/firmware, record `raw`, the decoded fields,
`supported`, `reason`, elapsed time and the observed physical condition for every row:

| Scenario | Expected observation |
| --- | --- |
| Paper loaded, cover closed | `paperPresent: true`, `coverOpen: false` |
| Paper removed, cover closed | `paperPresent: false` |
| Paper near end, if sensor exists | `paperNearEnd: true`; otherwise mark sensor unavailable |
| Cover open, with and without paper | `coverOpen: true`, or `printerStopped: true` on models that go silent while stopped; record whether paper state is retained |
| Printer powered off / cable removed | Unknown result, no indefinite wait or blocked ticket issuance |
| Query during a long print | `busy`, no interruption, truncation or duplicated ticket |
| Print immediately after starting a query | Print resumes after the bounded query, including timeout |
| Silent/unsupported printer or no input endpoint | Unknown/unsupported, no false paper/cover alarm |
| Repeated queries, including after a timeout | No old paper byte interpreted as cover; print still works |
| Same printer addressed by legacy id and stable ids | Cached aliases released, no claim conflict |
| Unstable cable, detach/replug and re-enumeration | Normal printing and the app's existing automatic reprint recover as before |
| Two USB printers / unrelated hub attach | Query leaves the other device's cached connection intact |

Record approved models and their reliable bits here only after this procedure is completed.
Every query logs its outcome unconditionally under the `ThermalPrinter` tag: `[status] port read=…`,
`[status] n=… read=… after … polls`, and the full snapshot. Filter logcat with
`ThermalPrinter:I ThermalPrinterUsbDiag:I Capacitor/Console:V '*:S'`. The wider `ThermalPrinterUsbDiag`
event trace is still gated by `USB_DIAG_VERBOSE`.

Before each scenario, power-cycle the printer. A printer left offline keeps a full receive buffer and
will fail the `DLE EOT` write of the next run, which invalidates the reading.

### getUsbDiagnostics(successCallback, errorCallback)

**Available since v1.2.0** — Returns a snapshot of the USB subsystem: devices reported by `UsbManager`
(with interfaces, endpoints and permission state), the plugin's connection cache, the sticky `USB_STATE`
extras, the current power/battery state, the most recent USB, power and screen events, and whether the
plugin's USB/power receiver is registered (`receiverRegistered`).

Useful when diagnosing field issues with OTG adapters, where the printer may leave the bus entirely.
TypeScript consumers can import the returned shape as `UsbDiagnostics`.

| Param           | Type                  | Description        |
| --------------- | --------------------- | ------------------ |
| successCallback | <code>function</code> | Result on success  |
| errorCallback   | <code>function</code> | Result on failure  |

Returned object:

| Field                  | Type                  | Description                                                                                       |
| ---------------------- | --------------------- | ------------------------------------------------------------------------------------------------- |
| timestamp, uptimeMs    | <code>number</code>   | Wall clock and `SystemClock.elapsedRealtime()` of the snapshot                                     |
| device                 | <code>string</code>   | Manufacturer, model, SDK level and build                                                          |
| receiverRegistered     | <code>boolean</code>  | `false` means the cache is only cleared on detach and on print failure                             |
| eventListenerAttached  | <code>boolean</code>  | Whether `onUsbEvent` currently has a subscriber                                                    |
| verboseLogging         | <code>boolean</code>  | Whether the per-print debug logging is compiled in                                                 |
| power                  | <code>object</code>   | `plugged`, `pluggedLabel`, `status`, `levelPercent`, `voltageMv`                                    |
| usbState               | <code>object</code>   | Sticky `USB_STATE` extras, stringified                                                            |
| usbDevices             | <code>Array</code>    | Devices on the bus, each with `deviceId`, ids, `interfaces` and `endpoints`                        |
| connectionCache        | <code>Array</code>    | The plugin's cached connections, with the `deviceId` each one was opened against                   |
| recentEvents           | <code>Array</code>    | Up to the last 200 USB, power and screen events                                                   |

<a name="onUsbEvent"></a>

### onUsbEvent(eventCallback, errorCallback)

**Available since v1.2.0** — Subscribes to USB attach/detach, `USB_STATE`, power and screen events.
The callback is kept and invoked for every event, each one carrying the action, the battery/power state and
the number of USB devices currently on the bus. Attach and detach events also carry the device descriptor.
TypeScript consumers can import the event shape as `UsbEvent`.

Typical use: react to `android.hardware.usb.action.USB_DEVICE_ATTACHED` to retry a print as soon as a
printer that dropped off the bus comes back. By the time the event reaches the callback, the plugin has
already dropped any cached connection whose device came back re-enumerated, so a print started from the
callback opens a fresh connection.

Subscribe once: calling it again replaces the previous subscription and releases the earlier callback.

| Param         | Type                  | Description                 |
| ------------- | --------------------- | --------------------------- |
| eventCallback | <code>function</code> | Called for every event      |
| errorCallback | <code>function</code> | Result on failure           |

<a name="bitmapToHexadecimalString"></a>

### bitmapToHexadecimalString(data, successCallback, errorCallback)

Convert Drawable instance to a hexadecimal string of the image data

| Param           | Type                                                                                               | Description                                                                                |
| --------------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| data            | <code>Array.&lt;Object&gt;</code>                                                                  | Data object                                                                                |
| data.type       | <code>&quot;bluetooth&quot;</code> \| <code>&quot;tcp&quot;</code> \| <code>&quot;usb&quot;</code> | List all bluetooth or usb printers                                                         |
| [data.id]       | <code>string</code> \| <code>number</code>                                                         | ID of printer to find (Bluetooth: address, TCP: Use address + port instead, USB: deviceId) |
| [data.address]  | <code>string</code>                                                                                | If type is "tcp" then the IP Address of the printer                                        |
| [data.port]     | <code>number</code>                                                                                | If type is "tcp" then the Port of the printer                                              |
| data.base64     | <code>string</code>                                                                                | Base64 encoded picture string to convert                                                   |
| successCallback | <code>function</code>                                                                              | Result on success                                                                          |
| errorCallback   | <code>function</code>                                                                              | Result on failure                                                                          |
