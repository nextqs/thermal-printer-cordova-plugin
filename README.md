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
