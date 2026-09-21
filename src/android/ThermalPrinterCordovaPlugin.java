package de.paystory.thermal_printer;

import static android.app.PendingIntent.FLAG_MUTABLE;

import android.Manifest;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;

import com.dantsu.escposprinter.EscPosCharsetEncoding;
import com.dantsu.escposprinter.EscPosPrinter;
import com.dantsu.escposprinter.connection.DeviceConnection;
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection;
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnections;
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections;
import com.dantsu.escposprinter.connection.tcp.TcpConnection;
import com.dantsu.escposprinter.connection.usb.UsbConnection;
import com.dantsu.escposprinter.connection.usb.UsbConnections;
import com.dantsu.escposprinter.exceptions.EscPosConnectionException;
import com.dantsu.escposprinter.textparser.PrinterTextParserImg;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ThermalPrinterCordovaPlugin extends CordovaPlugin {
    private static final String INTERNAL_UROVO_TYPE = "internal-urovo";
    private static final String INTERNAL_UROVO_ID = "internal-urovo";
    private static final String INTERNAL_UROVO_NAME = "Gertec GPOS820";
    private static final String PRINTER_MANAGER_CLASS = "android.device.PrinterManager";
    private static final int INTERNAL_PAGE_WIDTH = 384;
    private static final int INTERNAL_NO_ROTATE = 0;
    private static final int INTERNAL_FONT_SIZE = 26;
    private static final int INTERNAL_FONT_SIZE_SMALL = 22;
    private static final int INTERNAL_FONT_SIZE_TITLE = 34;
    private static final int INTERNAL_FONT_SIZE_TICKET = 72;
    private static final int INTERNAL_LINE_GAP = 6;
    private static final int INTERNAL_TEXT_HORIZONTAL_PADDING = 8;
    private static final int INTERNAL_BARCODE_QRCODE = 58;
    private static final int INTERNAL_QR_DEFAULT_SIZE = 280;
    private static final int INTERNAL_QR_MIN_SIZE = 240;
    private static final int INTERNAL_QR_MAX_SIZE = 320;
    private static final int INTERNAL_QR_X_MAX = 240;
    private static final int INTERNAL_LOGO_MAX_WIDTH = 220;
    private static final int INTERNAL_LOGO_GAP = 12;
    private static final int INTERNAL_WHITE_THRESHOLD = 245;
    private static final int INTERNAL_BOTTOM_FEED = 120;

    private final HashMap<String, DeviceConnection> connections = new HashMap<>();
    private BroadcastReceiver usbDetachReceiver;
    private boolean isUsbReceiverRegistered = false;

    // USB/power diagnostics (OTG investigation): last events kept in memory and optionally streamed to JS
    private static final String USB_DIAG_TAG = "ThermalPrinterUsbDiag";
    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final int USB_EVENT_HISTORY_SIZE = 200;
    /**
     * Per-print lifecycle lines and the full JSON dump of every event. Off for production: a kiosk prints
     * continuously, and getUsbDiagnostics() is the on-demand field tool. Flip to true to reproduce the
     * volume used during the OTG investigation. Attach/detach/power events are always logged, compactly.
     */
    private static final boolean USB_DIAG_VERBOSE = false;
    private final ArrayDeque<JSONObject> usbEventHistory = new ArrayDeque<>();
    private BroadcastReceiver usbDiagnosticsReceiver;
    private volatile boolean isUsbDiagnosticsReceiverRegistered = false;
    // Written by the Cordova thread pool, read by the event dispatcher: volatile for visibility
    private volatile CallbackContext usbEventListener;
    // Single thread: keeps event order while getting the binder calls and JSON off the main thread
    private volatile ExecutorService usbEventExecutor;
    private final AtomicLong printJobSequence = new AtomicLong();

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        usbEventExecutor = Executors.newSingleThreadExecutor();
        registerUsbDetachReceiver();
        registerUsbDiagnosticsReceiver();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterUsbDetachReceiver();
        unregisterUsbDiagnosticsReceiver();
        releaseUsbEventListener();
        if (usbEventExecutor != null) {
            usbEventExecutor.shutdown();
            usbEventExecutor = null;
        }
    }

    @Override
    public void onReset() {
        super.onReset();
        // The webview reloaded: the kept callback belongs to a page that no longer exists
        releaseUsbEventListener();
    }
    
    private void registerUsbDetachReceiver() {
        if (isUsbReceiverRegistered) {
            return;
        }
        
        usbDetachReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    UsbDevice device;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                    } else {
                        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    }
                    if (device != null) {
                        handleUsbDeviceDetached(device);
                    }
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                cordova.getActivity().registerReceiver(usbDetachReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                cordova.getActivity().registerReceiver(usbDetachReceiver, filter);
            }
            isUsbReceiverRegistered = true;
        } catch (Exception e) {
            android.util.Log.e("ThermalPrinter", "Failed to register USB detach receiver: " + e.getMessage());
        }
    }
    
    private void unregisterUsbDetachReceiver() {
        if (isUsbReceiverRegistered && usbDetachReceiver != null) {
            try {
                cordova.getActivity().unregisterReceiver(usbDetachReceiver);
                isUsbReceiverRegistered = false;
            } catch (Exception e) {
                android.util.Log.e("ThermalPrinter", "Failed to unregister USB detach receiver: " + e.getMessage());
            }
        }
    }
    
    private void handleUsbDeviceDetached(UsbDevice device) {
        android.util.Log.i("ThermalPrinter", "USB device detached: vendorId=" + device.getVendorId() + ", productId=" + device.getProductId());
        clearUsbConnections("device detached");
    }

    /**
     * Drops every cached USB connection. Called on detach and after a failed USB print, where the cached
     * connection is either gone or already proven unusable.
     */
    private void clearUsbConnections(String reason) {
        removeUsbConnections(listCachedUsbKeys(), null, reason);
    }

    /**
     * Drops only the cached USB connections whose device left the bus or came back re-enumerated with a new
     * deviceId (the file descriptor dies with the old enumeration). Connections to devices that are still
     * present on the same enumeration are kept, so attaching an unrelated device on a hub does not tear
     * down a print in flight. Called on USB_DEVICE_ATTACHED.
     */
    private void clearStaleUsbConnections(String reason) {
        ArrayList<String> staleKeys = new ArrayList<>();
        HashMap<String, DeviceConnection> staleConnections = new HashMap<>();

        for (String key : listCachedUsbKeys()) {
            DeviceConnection connection;
            synchronized (connections) {
                connection = connections.get(key);
            }
            if (connection == null) {
                continue;
            }
            // Validated outside the lock: isUsbDeviceStillPresent() does binder calls into UsbManager
            if (connection instanceof UsbConnection
                && isUsbDeviceStillPresent(((UsbConnection) connection).getDevice())) {
                continue;
            }
            staleKeys.add(key);
            staleConnections.put(key, connection);
        }

        // Pass what was judged stale: another thread may have replaced the entry while we validated
        removeUsbConnections(staleKeys, staleConnections, reason);
    }

    private ArrayList<String> listCachedUsbKeys() {
        ArrayList<String> keys = new ArrayList<>();
        // Thread-safe: synchronize access to connections HashMap
        synchronized (connections) {
            for (String key : connections.keySet()) {
                if (key.startsWith("usb-")) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /**
     * Removes each key from the cache before disconnecting it, so no other thread can pick the connection
     * up from the cache while it is being torn down.
     *
     * @param expected when given, a key whose cached connection is no longer the one mapped here was
     *                 replaced by another thread after it was selected, and is left alone: a stale-connection
     *                 sweep must never tear down a connection that was opened while it was running. Pass
     *                 null to remove whatever is cached under each key.
     */
    private void removeUsbConnections(ArrayList<String> keys, HashMap<String, DeviceConnection> expected, String reason) {
        if (keys.isEmpty()) {
            return;
        }

        int removed = 0;
        for (String key : keys) {
            DeviceConnection connection;
            synchronized (connections) {
                if (expected != null && connections.get(key) != expected.get(key)) {
                    android.util.Log.i("ThermalPrinter", "Cached USB connection replaced while validating, keeping it: " + key);
                    continue;
                }
                connection = connections.remove(key);
            }
            if (connection != null) {
                removed++;
                try {
                    connection.disconnect();
                    android.util.Log.i("ThermalPrinter", "Disconnected and removed cached USB connection: " + key + " (" + reason + ")");
                } catch (Exception e) {
                    android.util.Log.e("ThermalPrinter", "Error disconnecting: " + e.getMessage());
                }
            }
        }

        if (removed > 0) {
            android.util.Log.i("ThermalPrinter", "Cleared " + removed + " cached USB connection(s): " + reason);
        }
    }

    private void registerUsbDiagnosticsReceiver() {
        if (usbDiagnosticsReceiver != null) {
            return;
        }

        usbDiagnosticsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Read everything we need from the Intent while we are still inside onReceive, then hand
                // plain data over: the binder calls, the JSON and the log must not run on the main thread.
                final String action = intent.getAction();
                final UsbDevice device = extractUsbDevice(intent, action);
                final JSONObject extras = ACTION_USB_STATE.equals(action) ? extractExtras(intent) : null;
                dispatchUsbEvent(action, device, extras);
            }
        };

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            filter.addAction(ACTION_USB_STATE);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);

            // Application context so the receiver does not hold on to the Activity. It is still unregistered
            // in onDestroy() and registered again from initialize(), so it does not outlive the plugin.
            Context appContext = cordova.getActivity().getApplicationContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(usbDiagnosticsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appContext.registerReceiver(usbDiagnosticsReceiver, filter);
            }
            isUsbDiagnosticsReceiverRegistered = true;
            android.util.Log.i(USB_DIAG_TAG, "USB diagnostics receiver registered");
        } catch (Exception e) {
            usbDiagnosticsReceiver = null;
            isUsbDiagnosticsReceiverRegistered = false;
            // Without this receiver the cache is only cleared on detach and on print failure, so surface the
            // state in getUsbDiagnostics() instead of failing silently.
            android.util.Log.e(USB_DIAG_TAG, "Failed to register USB diagnostics receiver: " + e.getMessage());
        }
    }

    private void unregisterUsbDiagnosticsReceiver() {
        if (usbDiagnosticsReceiver == null) {
            return;
        }
        try {
            cordova.getActivity().getApplicationContext().unregisterReceiver(usbDiagnosticsReceiver);
        } catch (Exception e) {
            android.util.Log.e(USB_DIAG_TAG, "Failed to unregister USB diagnostics receiver: " + e.getMessage());
        }
        usbDiagnosticsReceiver = null;
        isUsbDiagnosticsReceiverRegistered = false;
    }

    private UsbDevice extractUsbDevice(Intent intent, String action) {
        if (!UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) && !UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            return null;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            }
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        } catch (Exception e) {
            android.util.Log.e(USB_DIAG_TAG, "Failed to read USB device from intent: " + e.getMessage());
            return null;
        }
    }

    private JSONObject extractExtras(Intent intent) {
        try {
            if (intent.getExtras() == null) {
                return null;
            }
            JSONObject extras = new JSONObject();
            for (String key : intent.getExtras().keySet()) {
                Object value = intent.getExtras().get(key);
                extras.put(key, value == null ? JSONObject.NULL : String.valueOf(value));
            }
            return extras;
        } catch (Exception e) {
            android.util.Log.e(USB_DIAG_TAG, "Failed to read intent extras: " + e.getMessage());
            return null;
        }
    }

    private void dispatchUsbEvent(final String action, final UsbDevice device, final JSONObject extras) {
        Runnable task = () -> {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                // Before notifying JS: a print started in reaction to this event must never find a cached
                // connection whose device came back re-enumerated.
                clearStaleUsbConnections("device attached");
            }
            recordUsbEvent(action, device, extras);
        };

        ExecutorService executor = usbEventExecutor;
        if (executor == null || executor.isShutdown()) {
            task.run();
            return;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
    }

    private void recordUsbEvent(String action, UsbDevice device, JSONObject extras) {
        JSONObject event = new JSONObject();
        JSONObject power = null;
        int usbDeviceCount = -1;
        try {
            event.put("timestamp", System.currentTimeMillis());
            event.put("uptimeMs", SystemClock.elapsedRealtime());
            event.put("action", action);

            if (device != null) {
                event.put("device", describeUsbDevice(device, null));
            }
            if (extras != null) {
                event.put("extras", extras);
            }

            power = readPowerState();
            usbDeviceCount = countUsbDevices();
            event.put("power", power);
            event.put("usbDeviceCount", usbDeviceCount);
        } catch (Exception e) {
            android.util.Log.e(USB_DIAG_TAG, "Failed to build USB event: " + e.getMessage());
        }

        // One compact line per event: this is the trace the field procedure looks for. The full payload is
        // available on demand through getUsbDiagnostics(), which also returns the last events.
        android.util.Log.i(USB_DIAG_TAG, "[event] " + action
            + " usbDeviceCount=" + usbDeviceCount
            + " plugged=" + (power == null ? "?" : power.optString("pluggedLabel", "?"))
            + " level=" + (power == null ? -1 : power.optInt("levelPercent", -1)) + "%");
        if (USB_DIAG_VERBOSE) {
            android.util.Log.i(USB_DIAG_TAG, "[event][full] " + event);
        }

        synchronized (usbEventHistory) {
            usbEventHistory.addLast(event);
            while (usbEventHistory.size() > USB_EVENT_HISTORY_SIZE) {
                usbEventHistory.removeFirst();
            }
        }

        CallbackContext listener = usbEventListener;
        if (listener != null) {
            try {
                PluginResult result = new PluginResult(PluginResult.Status.OK, event);
                result.setKeepCallback(true);
                listener.sendPluginResult(result);
            } catch (Exception e) {
                android.util.Log.e(USB_DIAG_TAG, "Failed to deliver USB event: " + e.getMessage());
            }
        }
    }

    private void registerUsbEventListener(CallbackContext callbackContext) {
        CallbackContext previous = usbEventListener;
        usbEventListener = callbackContext;
        // Release the previous subscription instead of leaving its kept callback dangling in the bridge
        if (previous != null && previous != callbackContext) {
            finishUsbEventListener(previous);
        }

        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }

    private void releaseUsbEventListener() {
        CallbackContext listener = usbEventListener;
        usbEventListener = null;
        finishUsbEventListener(listener);
    }

    private void finishUsbEventListener(CallbackContext listener) {
        if (listener == null) {
            return;
        }
        try {
            PluginResult done = new PluginResult(PluginResult.Status.NO_RESULT);
            done.setKeepCallback(false);
            listener.sendPluginResult(done);
        } catch (Exception ignored) {}
    }

    private int countUsbDevices() {
        try {
            UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
            return usbManager == null ? -1 : usbManager.getDeviceList().size();
        } catch (Exception e) {
            return -1;
        }
    }

    private JSONObject readPowerState() throws JSONException {
        JSONObject power = new JSONObject();
        Intent battery = cordova.getActivity().getApplicationContext()
            .registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            power.put("plugged", plugged);
            power.put("pluggedLabel", plugged == BatteryManager.BATTERY_PLUGGED_AC ? "ac"
                : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "usb"
                : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless"
                : plugged == 0 ? "unplugged" : "other");
            power.put("status", battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1));
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            power.put("levelPercent", level >= 0 && scale > 0 ? (level * 100) / scale : -1);
            power.put("voltageMv", battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1));
        }
        return power;
    }

    private JSONObject describeUsbDevice(UsbDevice device, UsbManager usbManager) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("deviceName", device.getDeviceName());
        obj.put("deviceId", device.getDeviceId());
        obj.put("vendorId", device.getVendorId());
        obj.put("productId", device.getProductId());
        obj.put("deviceClass", device.getDeviceClass());
        obj.put("interfaceCount", device.getInterfaceCount());
        try { obj.put("productName", device.getProductName()); } catch (Exception ignored) {}
        try { obj.put("manufacturerName", device.getManufacturerName()); } catch (Exception ignored) {}
        if (usbManager != null) {
            try { obj.put("hasPermission", usbManager.hasPermission(device)); } catch (Exception ignored) {}
            try { obj.put("serialNumber", device.getSerialNumber()); } catch (Exception ignored) {}
        }
        JSONArray interfaces = new JSONArray();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            JSONObject ifaceObj = new JSONObject();
            ifaceObj.put("id", usbInterface.getId());
            ifaceObj.put("class", usbInterface.getInterfaceClass());
            ifaceObj.put("subclass", usbInterface.getInterfaceSubclass());
            ifaceObj.put("protocol", usbInterface.getInterfaceProtocol());
            JSONArray endpoints = new JSONArray();
            for (int e = 0; e < usbInterface.getEndpointCount(); e++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(e);
                endpoints.put(new JSONObject()
                    .put("address", endpoint.getAddress())
                    .put("type", endpoint.getType())
                    .put("direction", endpoint.getDirection() == UsbConstants.USB_DIR_OUT ? "out" : "in")
                    .put("maxPacketSize", endpoint.getMaxPacketSize()));
            }
            ifaceObj.put("endpoints", endpoints);
            interfaces.put(ifaceObj);
        }
        obj.put("interfaces", interfaces);
        return obj;
    }

    private void getUsbDiagnostics(CallbackContext callbackContext) {
        try {
            JSONObject result = new JSONObject();
            result.put("timestamp", System.currentTimeMillis());
            result.put("uptimeMs", SystemClock.elapsedRealtime());
            result.put("device", Build.MANUFACTURER + " " + Build.MODEL + " (SDK " + Build.VERSION.SDK_INT + ", " + Build.DISPLAY + ")");
            // If the receiver failed to register, the cache is only cleared on detach and on print failure
            result.put("receiverRegistered", isUsbDiagnosticsReceiverRegistered);
            result.put("eventListenerAttached", usbEventListener != null);
            result.put("verboseLogging", USB_DIAG_VERBOSE);
            result.put("power", readPowerState());

            Intent usbState = cordova.getActivity().getApplicationContext()
                .registerReceiver(null, new IntentFilter(ACTION_USB_STATE));
            if (usbState != null && usbState.getExtras() != null) {
                JSONObject extras = new JSONObject();
                for (String key : usbState.getExtras().keySet()) {
                    Object value = usbState.getExtras().get(key);
                    extras.put(key, value == null ? JSONObject.NULL : String.valueOf(value));
                }
                result.put("usbState", extras);
            }

            UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
            JSONArray devices = new JSONArray();
            if (usbManager != null) {
                for (UsbDevice device : usbManager.getDeviceList().values()) {
                    devices.put(describeUsbDevice(device, usbManager));
                }
            }
            result.put("usbDevices", devices);

            JSONArray cache = new JSONArray();
            synchronized (connections) {
                for (String key : connections.keySet()) {
                    DeviceConnection connection = connections.get(key);
                    JSONObject entry = new JSONObject();
                    entry.put("key", key);
                    entry.put("isConnected", connection != null && connection.isConnected());
                    if (connection instanceof UsbConnection && ((UsbConnection) connection).getDevice() != null) {
                        UsbDevice cachedDevice = ((UsbConnection) connection).getDevice();
                        entry.put("deviceName", cachedDevice.getDeviceName());
                        entry.put("deviceId", cachedDevice.getDeviceId());
                    }
                    cache.put(entry);
                }
            }
            result.put("connectionCache", cache);

            JSONArray events = new JSONArray();
            synchronized (usbEventHistory) {
                for (JSONObject event : usbEventHistory) {
                    events.put(event);
                }
            }
            result.put("recentEvents", events);

            android.util.Log.i(USB_DIAG_TAG, "[diagnostics] devices=" + devices.length() + " cache=" + cache);
            callbackContext.success(result);
        } catch (Exception e) {
            final String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            android.util.Log.e(USB_DIAG_TAG, "[diagnostics] error: " + errorMsg, e);
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", errorMsg);
            }}));
        }
    }

    @Override
    public boolean execute(String action, JSONArray args,
                           final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                if (action.equals("listPrinters")) {
                    try {
                        ThermalPrinterCordovaPlugin.this.listPrinters(callbackContext, args.getJSONObject(0));
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else if (action.equals("getUsbDiagnostics")) {
                    ThermalPrinterCordovaPlugin.this.getUsbDiagnostics(callbackContext);
                } else if (action.equals("registerUsbEventListener")) {
                    ThermalPrinterCordovaPlugin.this.registerUsbEventListener(callbackContext);
                } else if (action.startsWith("printFormattedText")) {
                    ThermalPrinterCordovaPlugin.this.printFormattedText(callbackContext, action, args.getJSONObject(0));
                } else if (action.equals("getEncoding")) {
                    ThermalPrinterCordovaPlugin.this.getEncoding(callbackContext, args.getJSONObject(0));
                } else if (action.equals("disconnectPrinter")) {
                    ThermalPrinterCordovaPlugin.this.disconnectPrinter(callbackContext, args.getJSONObject(0));
                } else if (action.equals("requestPermissions")) {
                    ThermalPrinterCordovaPlugin.this.requestUSBPermissions(callbackContext, args.getJSONObject(0));
                } else if (action.equals("bitmapToHexadecimalString")) {
                    ThermalPrinterCordovaPlugin.this.bitmapToHexadecimalString(callbackContext, args.getJSONObject(0));
                } else if (action.equals("printInternalUrovoPage")) {
                    ThermalPrinterCordovaPlugin.this.printInternalUrovoPage(callbackContext, args.getJSONObject(0));
                }
            } catch (JSONException exception) {
                callbackContext.error(exception.getMessage());
            }
        });

        return true;
    }

    private void bitmapToHexadecimalString(CallbackContext callbackContext, JSONObject data) throws JSONException {
        if (isInternalUrovo(data)) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", "bitmapToHexadecimalString is not supported for internal-urovo printers");
                put("type", INTERNAL_UROVO_TYPE);
            }}));
            return;
        }

        String encodedString = data.getString("base64");
        byte[] decodedString = Base64.decode(encodedString.contains(",")
            ? encodedString.substring(encodedString.indexOf(",") + 1) : encodedString, Base64.DEFAULT);
        data.put("bytes", decodedString);
        this.bytesToHexadecimalString(callbackContext, data);
    }

    private void bytesToHexadecimalString(CallbackContext callbackContext, JSONObject data) throws JSONException {
        EscPosPrinter printer = this.getPrinter(callbackContext, data);
        try {
            byte[] bytes = (byte[]) data.get("bytes");
            Bitmap decodedByte = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            callbackContext.success(PrinterTextParserImg.bitmapToHexadecimalString(printer, decodedByte));
        } catch (Exception e) {
            final String errorMsg = e.getMessage();
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", errorMsg);
            }}));
        }
    }

    private void requestUSBPermissions(CallbackContext callbackContext, JSONObject data) throws JSONException {
        if (isInternalUrovo(data)) {
            callbackContext.success(new JSONObject(new HashMap<String, Object>() {{
                put("granted", true);
            }}));
            return;
        }

        DeviceConnection connection = ThermalPrinterCordovaPlugin.this.getPrinterConnection(callbackContext, data);
        if (connection != null) {
            // Use stable key instead of deviceId (which changes after unplug/replug)
            String intentName = "thermalPrinterUSBRequest-" + buildConnectionKey(data);

            Intent explicitIntent = new Intent(intentName);
            explicitIntent.setPackage(cordova.getActivity().getPackageName()); // Make the Intent explicit
            int pendingIntentFlags = FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                cordova.getActivity().getBaseContext(),
                0,
                explicitIntent,
                pendingIntentFlags
            );

            ArrayList<BroadcastReceiver> broadcastReceiverArrayList = new ArrayList<>();
            BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action != null && action.equals(intentName)) {
                        for (BroadcastReceiver br : broadcastReceiverArrayList) {
                            if (br != null) {
                                try {
                                    cordova.getActivity().unregisterReceiver(br);
                                } catch (Exception ignored) {
                                    // Prevent crash if receiver is already unregistered
                                }
                            }
                        }

                        synchronized (this) {
                            UsbManager usbManager = (UsbManager) ThermalPrinterCordovaPlugin.this.cordova.getActivity().getSystemService(Context.USB_SERVICE);
                            UsbDevice usbDevice;
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                            } else {
                                usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                            }
                            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                if (usbManager != null && usbDevice != null) {
                                    callbackContext.success(new JSONObject(new HashMap<String, Object>() {{
                                        put("granted", true);
                                    }}));
                                    return;
                                }
                            }
                            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                                put("granted", false);
                            }}));
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter(intentName);

            // Use the appropriate method to register the BroadcastReceiver according to the API version
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {  // TIRAMISU is Android 13 / API 33
                cordova.getActivity().registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                cordova.getActivity().registerReceiver(broadcastReceiver, filter);
            }

            broadcastReceiverArrayList.add(broadcastReceiver);

            UsbManager usbManager = (UsbManager) this.cordova.getActivity().getSystemService(Context.USB_SERVICE);
            if (usbManager != null) {
                usbManager.requestPermission(((UsbConnection) connection).getDevice(), permissionIntent);
            }
        }
    }

    private void listPrinters(CallbackContext callbackContext, JSONObject data) throws JSONException {
        JSONArray printers = new JSONArray();

        String type = data.getString("type");
        if (INTERNAL_UROVO_TYPE.equals(type)) {
            if (hasInternalPrinterManager()) {
                JSONObject printerObj = new JSONObject();
                printerObj.put("id", INTERNAL_UROVO_ID);
                printerObj.put("name", INTERNAL_UROVO_NAME);
                printerObj.put("type", INTERNAL_UROVO_TYPE);
                printerObj.put("manufacturer", Build.MANUFACTURER);
                printerObj.put("brand", Build.BRAND);
                printerObj.put("model", Build.MODEL);
                printerObj.put("device", Build.DEVICE);
                printers.put(printerObj);
            }
            callbackContext.success(printers);
            return;
        }

        if (type.equals("bluetooth")) {
            if (!this.cordova.hasPermission(Manifest.permission.BLUETOOTH)) {
                callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                    put("error", "Missing permission for " + Manifest.permission.BLUETOOTH);
                }}));
                return;
            }
            if (!this.checkBluetooth(callbackContext)) {
                return;
            }
            try {
                BluetoothConnections printerConnections = new BluetoothConnections();
                for (BluetoothConnection bluetoothConnection : printerConnections.getList()) {
                    BluetoothDevice bluetoothDevice = bluetoothConnection.getDevice();
                    JSONObject printerObj = new JSONObject();
                    try { printerObj.put("address", bluetoothDevice.getAddress()); } catch (Exception ignored) {}
                    try { printerObj.put("bondState", bluetoothDevice.getBondState()); } catch (Exception ignored) {}
                    try { printerObj.put("name", bluetoothDevice.getName()); } catch (Exception ignored) {}
                    try { printerObj.put("type", bluetoothDevice.getType()); } catch (Exception ignored) {}
                    try { printerObj.put("features", bluetoothDevice.getUuids()); } catch (Exception ignored) {}
                    try { printerObj.put("deviceClass", bluetoothDevice.getBluetoothClass().getDeviceClass()); } catch (Exception ignored) {}
                    try { printerObj.put("majorDeviceClass", bluetoothDevice.getBluetoothClass().getMajorDeviceClass()); } catch (Exception ignored) {}
                    printers.put(printerObj);
                }
            } catch (Exception e) {
                final String errorMsg = e.getMessage();
                callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                    put("error", errorMsg);
                }}));
                return;
            }
        } else if (type.equals("usb")) {
            UsbConnections printerConnections = new UsbConnections(this.cordova.getActivity());
            for (UsbConnection usbConnection : printerConnections.getList()) {
                UsbDevice usbDevice = usbConnection.getDevice();
                JSONObject printerObj = new JSONObject();
                try { printerObj.put("productName", Objects.requireNonNull(usbDevice.getProductName()).trim()); } catch (Exception ignored) {}
                try { printerObj.put("manufacturerName", usbDevice.getManufacturerName()); } catch (Exception ignored) {}
                try { printerObj.put("deviceId", usbDevice.getDeviceId()); } catch (Exception ignored) {}
                try { printerObj.put("serialNumber", usbDevice.getSerialNumber()); } catch (Exception ignored) {}
                try { printerObj.put("vendorId", usbDevice.getVendorId()); } catch (Exception ignored) {}
                try { printerObj.put("productId", usbDevice.getProductId()); } catch (Exception ignored) {}
                printers.put(printerObj);
            }
        } else {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", "Unsupported printer type: " + type);
                put("type", type);
            }}));
            return;
        }

        callbackContext.success(printers);
    }

    private void printFormattedText(CallbackContext callbackContext, String action, JSONObject data) throws JSONException {
        if (isInternalUrovo(data)) {
            this.printInternalUrovoFormattedText(callbackContext, data);
            return;
        }

        final long jobId = printJobSequence.incrementAndGet();
        final long jobStart = SystemClock.elapsedRealtime();
        final boolean isUsb = "usb".equals(data.optString("type"));
        if (isUsb && USB_DIAG_VERBOSE) {
            android.util.Log.i(USB_DIAG_TAG, "[print #" + jobId + "] start key=" + buildConnectionKey(data)
                + " id=" + data.optString("id") + " textLength=" + data.optString("text").length()
                + " usbDeviceCount=" + countUsbDevices());
        }

        EscPosPrinter printer;
        try {
            printer = this.getPrinter(callbackContext, data);
        } catch (JSONException e) {
            if (isUsb) {
                android.util.Log.e(USB_DIAG_TAG, "[print #" + jobId + "] getPrinter failed after "
                    + (SystemClock.elapsedRealtime() - jobStart) + "ms: " + e.getMessage());
            }
            throw e;
        }
        if (isUsb && USB_DIAG_VERBOSE) {
            android.util.Log.i(USB_DIAG_TAG, "[print #" + jobId + "] printer ready after "
                + (SystemClock.elapsedRealtime() - jobStart) + "ms");
        }
        try {
            // Read printerModel parameter (optional)
            String printerModel = data.optString("printerModel", "");
            // Enable automatic slicing for Gertec printers
            if ("gertec".equalsIgnoreCase(printerModel)) {
                printer.setImageSlicing(true);
                printer.setImageSliceLinesPerStrip(20);
            }
            
            int dotsFeedPaper = data.has("mmFeedPaper")
                ? printer.mmToPx((float) data.getDouble("mmFeedPaper"))
                : data.optInt("dotsFeedPaper", 20);
            if (action.endsWith("Cut")) {
                printer.printFormattedTextAndCut(data.getString("text"), dotsFeedPaper);
            } else {
                printer.printFormattedText(data.getString("text"), dotsFeedPaper);
            }
            if (isUsb && USB_DIAG_VERBOSE) {
                android.util.Log.i(USB_DIAG_TAG, "[print #" + jobId + "] success in "
                    + (SystemClock.elapsedRealtime() - jobStart) + "ms");
            }
            callbackContext.success();
        } catch (EscPosConnectionException e) {
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "EscPosConnectionException occurred";
            if (isUsb) {
                android.util.Log.e(USB_DIAG_TAG, "[print #" + jobId + "] CONNECTION_ERROR after "
                    + (SystemClock.elapsedRealtime() - jobStart) + "ms: " + errorMsg);
                clearUsbConnections("print connection error");
            }
            android.util.Log.e("ThermalPrinter", "Print connection error: " + errorMsg, e);
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", "Connection error: " + errorMsg);
                put("type", "CONNECTION_ERROR");
            }}));
        } catch (Exception e) {
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error during print: " + e.getClass().getSimpleName();
            if (isUsb) {
                android.util.Log.e(USB_DIAG_TAG, "[print #" + jobId + "] PRINT_ERROR after "
                    + (SystemClock.elapsedRealtime() - jobStart) + "ms: " + errorMsg);
                // Kept deliberately broad: any unexpected failure on a USB print may leave the connection
                // unusable, and the cost of dropping it is one re-enumeration on the next print.
                clearUsbConnections("print error");
            }
            android.util.Log.e("ThermalPrinter", "Print error: " + errorMsg, e);
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", errorMsg);
                put("type", "PRINT_ERROR");
            }}));
        }
    }

    private void getEncoding(CallbackContext callbackContext, JSONObject data) throws JSONException {
        if (isInternalUrovo(data)) {
            callbackContext.success(new JSONObject(new HashMap<String, Object>() {{
                put("name", INTERNAL_UROVO_TYPE);
            }}));
            return;
        }

        EscPosPrinter printer = this.getPrinter(callbackContext, data);
        callbackContext.success(new JSONObject(new HashMap<String, Object>() {{
            EscPosCharsetEncoding encoding = printer.getEncoding();
            if (encoding != null) {
                callbackContext.success(new JSONObject(new HashMap<String, Object>() {{
                    put("name", encoding.getName());
                    put("command", encoding.getCommand());
                }}));
            } else {
                callbackContext.success("null");
            }
        }}));
    }

    private void disconnectPrinter(CallbackContext callbackContext, JSONObject data) throws JSONException {
        if (isInternalUrovo(data)) {
            callbackContext.success();
            return;
        }

        EscPosPrinter printer = this.getPrinter(callbackContext, data);
        printer.disconnectPrinter();
        callbackContext.success();
    }

    private String buildConnectionKey(JSONObject data) throws JSONException {
        String type = data.getString("type");
        if (INTERNAL_UROVO_TYPE.equals(type)) {
            return INTERNAL_UROVO_TYPE + "-" + data.optString("id", INTERNAL_UROVO_ID);
        }
        if (!"usb".equals(type)) {
            return type + "-" + data.optString("id");
        }

        // For USB: prefer stable identifiers (vendorId + productId + serialNumber)
        int vendorId = data.optInt("vendorId", -1);
        int productId = data.optInt("productId", -1);
        String serial = data.optString("serialNumber", "");

        // Fallback to old id if vendor/product are not provided (backward compatibility)
        if (vendorId <= 0 || productId <= 0) {
            String fallbackKey = "usb-" + data.optString("id");
            android.util.Log.d("ThermalPrinter", "Using fallback USB key (no vendor/product): " + fallbackKey);
            return fallbackKey;
        }

        if (serial != null && !serial.trim().isEmpty()) {
            String key = "usb-" + vendorId + "-" + productId + "-" + serial.trim();
            android.util.Log.d("ThermalPrinter", "Using stable USB key with serial: " + key);
            return key;
        }
        String key = "usb-" + vendorId + "-" + productId;
        android.util.Log.d("ThermalPrinter", "Using stable USB key: " + key);
        return key;
    }

    private DeviceConnection getDevice(CallbackContext callbackContext, JSONObject data) throws JSONException {
        String type = data.getString("type");
        String hashKey = buildConnectionKey(data);
        
        // Thread-safe: check cache with synchronization
        DeviceConnection cachedConnection = null;
        boolean needsValidation = false;
        synchronized (connections) {
            if (this.connections.containsKey(hashKey)) {
                cachedConnection = this.connections.get(hashKey);
                if (cachedConnection != null) {
                    // For USB, we need to validate outside the lock
                    if (type.equals("usb") && cachedConnection instanceof UsbConnection) {
                        needsValidation = true;
                    } else if (cachedConnection.isConnected()) {
                        return cachedConnection;
                    } else {
                        this.connections.remove(hashKey);
                        cachedConnection = null;
                    }
                }
            }
        }
        
        // USB validation (outside lock to avoid blocking other threads)
        if (needsValidation && cachedConnection != null) {
            UsbConnection usbConnection = (UsbConnection) cachedConnection;
            if (!isUsbDeviceStillPresent(usbConnection.getDevice())) {
                android.util.Log.w("ThermalPrinter", "USB device no longer present, removing cached connection");
                try {
                    cachedConnection.disconnect();
                } catch (Exception e) {
                    android.util.Log.e("ThermalPrinter", "Error disconnecting stale connection: " + e.getMessage());
                }
                synchronized (connections) {
                    this.connections.remove(hashKey);
                }
                cachedConnection = null;
            } else if (cachedConnection.isConnected()) {
                android.util.Log.d("ThermalPrinter", "Reusing cached USB connection: " + hashKey);
                return cachedConnection;
            } else {
                android.util.Log.w("ThermalPrinter", "Cached USB connection not connected, removing");
                synchronized (connections) {
                    this.connections.remove(hashKey);
                }
                cachedConnection = null;
            }
        }

        // Create new connection
        String id = data.optString("id");
        String address = data.optString("address");
        int port = data.optInt("port", 9100);

        if (type.equals("bluetooth")) {
            if (!this.checkBluetooth(callbackContext)) {
                return null;
            }
            if (!this.cordova.hasPermission(Manifest.permission.BLUETOOTH)) {
                callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                    put("error", "Missing permission for " + Manifest.permission.DISABLE_KEYGUARD);
                }}));
                return null;
            }
            if (id.equals("first")) {
                return BluetoothPrintersConnections.selectFirstPaired();
            }
            BluetoothConnections printerConnections = new BluetoothConnections();
            for (BluetoothConnection bluetoothConnection : printerConnections.getList()) {
                BluetoothDevice bluetoothDevice = bluetoothConnection.getDevice();
                try { if (bluetoothDevice.getAddress().equals(id)) { return bluetoothConnection; } } catch (Exception ignored) {}
                try { if (bluetoothDevice.getName().equals(id)) { return bluetoothConnection; } } catch (Exception ignored) {}
            }
        } else if (type.equals("tcp")) {
            return new TcpConnection(address, port);
        } else {
            // USB device matching
            int vendorId = data.optInt("vendorId", -1);
            int productId = data.optInt("productId", -1);
            String serialNumber = data.optString("serialNumber", "");

            UsbConnections printerConnections = new UsbConnections(this.cordova.getActivity());
            
            // Preferred: match by vendorId + productId (+ serial if provided)
            if (vendorId > 0 && productId > 0) {
                android.util.Log.d("ThermalPrinter", "USB matching by vendor/product: " + vendorId + "/" + productId);
                for (UsbConnection usbConnection : printerConnections.getList()) {
                    UsbDevice usbDevice = usbConnection.getDevice();
                    if (usbDevice.getVendorId() == vendorId && usbDevice.getProductId() == productId) {
                        // If serial provided, match it too
                        if (serialNumber != null && !serialNumber.trim().isEmpty()) {
                            try {
                                String deviceSerial = usbDevice.getSerialNumber();
                                if (serialNumber.equals(deviceSerial)) {
                                    android.util.Log.i("ThermalPrinter", "USB device matched by vendor/product/serial");
                                    return usbConnection;
                                }
                            } catch (Exception ignored) {
                                // If cannot read serial, accept vendor/product match
                                android.util.Log.i("ThermalPrinter", "USB device matched by vendor/product (serial unreadable)");
                                return usbConnection;
                            }
                        } else {
                            android.util.Log.i("ThermalPrinter", "USB device matched by vendor/product");
                            return usbConnection;
                        }
                    }
                }
            }
            
            // Fallback: match by deviceId or productName (backward compatibility)
            android.util.Log.d("ThermalPrinter", "USB fallback matching by id: " + id);
            for (UsbConnection usbConnection : printerConnections.getList()) {
                UsbDevice usbDevice = usbConnection.getDevice();
                try { 
                    if (usbDevice.getDeviceId() == Integer.parseInt(id)) { 
                        android.util.Log.i("ThermalPrinter", "USB device matched by deviceId (fallback)");
                        return usbConnection; 
                    } 
                } catch (Exception ignored) {}
                try { 
                    if (Objects.requireNonNull(usbDevice.getProductName()).trim().equals(id)) { 
                        android.util.Log.i("ThermalPrinter", "USB device matched by productName (fallback)");
                        return usbConnection; 
                    } 
                } catch (Exception ignored) {}
            }
        }

        return null;
    }
    
    private boolean isUsbDeviceStillPresent(UsbDevice cachedDevice) {
        if (cachedDevice == null) {
            return false;
        }
        
        try {
            UsbManager usbManager = (UsbManager) this.cordova.getActivity().getSystemService(Context.USB_SERVICE);
            if (usbManager == null) {
                return false;
            }
            
            int deviceId = cachedDevice.getDeviceId();
            int vendorId = cachedDevice.getVendorId();
            int productId = cachedDevice.getProductId();
            String serial = "";
            try {
                serial = cachedDevice.getSerialNumber();
            } catch (Exception ignored) {}

            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            for (UsbDevice device : deviceList.values()) {
                // deviceId is the one identifier that DOES change when the device re-enumerates, and the
                // open file descriptor dies with the old enumeration. A vendor/product/serial match on a
                // new deviceId is the same printer but not the same connection: the cached UsbConnection
                // would still report isConnected() (outputStream != null) and then fail in claimInterface.
                if (device.getDeviceId() != deviceId) {
                    continue;
                }
                // Match by vendorId + productId, to be sure the reused deviceId is the same device
                if (device.getVendorId() == vendorId && device.getProductId() == productId) {
                    // If we have serial for cached device, verify it matches
                    if (serial != null && !serial.isEmpty()) {
                        try {
                            String currentSerial = device.getSerialNumber();
                            if (serial.equals(currentSerial)) {
                                return true;
                            }
                        } catch (Exception ignored) {
                            // If cannot read serial, consider vendor/product match sufficient
                            return true;
                        }
                    } else {
                        // No serial on cached device, vendor/product match is sufficient
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("ThermalPrinter", "Error checking USB device presence: " + e.getMessage());
        }
        
        return false;
    }

    private boolean isInternalUrovo(JSONObject data) {
        return INTERNAL_UROVO_TYPE.equals(data.optString("type", ""));
    }

    private boolean hasInternalPrinterManager() {
        try {
            Class.forName(PRINTER_MANAGER_CLASS);
            return true;
        } catch (ClassNotFoundException error) {
            return false;
        }
    }

    private Object createInternalPrinterManager() throws Exception {
        Class<?> printerManagerClass = Class.forName(PRINTER_MANAGER_CLASS);
        return printerManagerClass.getConstructor().newInstance();
    }

    private void printInternalUrovoFormattedText(CallbackContext callbackContext, JSONObject data) throws JSONException {
        InternalPrinterMethods methods = null;
        try {
            if (!hasInternalPrinterManager()) {
                throw new IllegalStateException("Internal printer manager is not available");
            }

            Object printerManager = createInternalPrinterManager();
            methods = new InternalPrinterMethods(printerManager);

            int openResult = methods.open();
            if (openResult != 0) {
                throw new IllegalStateException("PrinterManager.open returned " + openResult);
            }

            int printStatus;
            try {
                methods.setupPage(INTERNAL_PAGE_WIDTH, -1);
                methods.clearPage();

                int y = 0;
                drawInternalFormattedText(methods, data.getString("text"), y);
                printStatus = methods.printPage(INTERNAL_NO_ROTATE);
                if (printStatus != 0) {
                    throw new IllegalStateException("PrinterManager.printPage returned " + printStatus);
                }
                methods.paperFeed(INTERNAL_BOTTOM_FEED);
            } finally {
                try {
                    methods.close();
                } catch (Exception closeError) {
                    android.util.Log.w("ThermalPrinter", "Internal printer close failed: " + closeError.getMessage());
                }
            }

            JSONObject result = new JSONObject();
            result.put("status", printStatus);
            callbackContext.success(result);
        } catch (Exception e) {
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown internal printer error";
            android.util.Log.e("ThermalPrinter", "Internal printer error: " + errorMsg, e);
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", errorMsg);
                put("type", INTERNAL_UROVO_TYPE);
            }}));
        }
    }

    private void printInternalUrovoPage(CallbackContext callbackContext, JSONObject data) throws JSONException {
        InternalPrinterMethods methods = null;
        try {
            if (!hasInternalPrinterManager()) {
                throw new IllegalStateException("Internal printer manager is not available");
            }

            Object printerManager = createInternalPrinterManager();
            methods = new InternalPrinterMethods(printerManager);

            int openResult = methods.open();
            if (openResult != 0) {
                throw new IllegalStateException("PrinterManager.open returned " + openResult);
            }

            int printStatus;
            try {
                methods.setupPage(INTERNAL_PAGE_WIDTH, -1);
                methods.clearPage();

                JSONArray operations = data.getJSONArray("operations");
                int y = 0;
                for (int i = 0; i < operations.length(); i++) {
                    JSONObject op = operations.getJSONObject(i);
                    String kind = op.getString("kind");
                    int gap = op.optInt("gap", 0);

                    if ("gap".equals(kind)) {
                        y += op.optInt("dots", 0);

                    } else if ("text".equals(kind)) {
                        String text = op.optString("text", "").trim();
                        if (text.isEmpty()) { y += INTERNAL_LINE_GAP; continue; }
                        String align = op.optString("align", "left");
                        boolean bold = op.optBoolean("bold", false);
                        int fontSize = urovoFontSizeFromName(op.optString("size", "normal"));
                        ArrayList<String> lines = wrapInternalText(text, fontSize);
                        for (String line : lines) {
                            int x = getInternalApproximateX(line, align, fontSize);
                            int height = methods.drawText(line, x, y, "", fontSize, bold, false, INTERNAL_NO_ROTATE);
                            y += Math.max(height, fontSize + INTERNAL_LINE_GAP);
                        }
                        y += gap;

                    } else if ("qr".equals(kind)) {
                        String value = op.optString("value", "").trim();
                        if (value.isEmpty()) { continue; }
                        String align = op.optString("align", "center");
                        int size = Math.min(INTERNAL_QR_MAX_SIZE, Math.max(INTERNAL_QR_MIN_SIZE, op.optInt("size", INTERNAL_QR_DEFAULT_SIZE)));
                        int x;
                        if (op.has("x")) {
                            x = Math.max(0, Math.min(op.optInt("x", 0), INTERNAL_QR_X_MAX));
                        } else {
                            x = getInternalAlignedX(size, align);
                        }
                        y += Math.max(0, op.optInt("topGap", 0));
                        int height = methods.drawBarcode(value, x, y, INTERNAL_BARCODE_QRCODE, 5, size, INTERNAL_NO_ROTATE);
                        y += Math.max(height, 0) + gap;

                    } else if ("image".equals(kind)) {
                        Bitmap image = decodeInternalImage(op.optString("base64", ""));
                        if (image != null) {
                            Bitmap trimmed = trimInternalBitmap(image);
                            Bitmap scaled = scaleInternalBitmap(trimmed, INTERNAL_LOGO_MAX_WIDTH);
                            String align = op.optString("align", "center");
                            int x = getInternalAlignedX(scaled.getWidth(), align);
                            int height = methods.drawBitmap(scaled, x, y);
                            y += Math.max(height, scaled.getHeight()) + INTERNAL_LOGO_GAP + gap;
                        }
                    }
                }

                printStatus = methods.printPage(INTERNAL_NO_ROTATE);
                if (printStatus != 0) {
                    throw new IllegalStateException("PrinterManager.printPage returned " + printStatus);
                }
                methods.paperFeed(INTERNAL_BOTTOM_FEED);
            } finally {
                try {
                    methods.close();
                } catch (Exception closeError) {
                    android.util.Log.w("ThermalPrinter", "Internal printer close failed: " + closeError.getMessage());
                }
            }

            JSONObject result = new JSONObject();
            result.put("status", printStatus);
            callbackContext.success(result);
        } catch (Exception e) {
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown internal printer error";
            android.util.Log.e("ThermalPrinter", "Internal printer page error: " + errorMsg, e);
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", errorMsg);
                put("type", INTERNAL_UROVO_TYPE);
            }}));
        }
    }

    private int urovoFontSizeFromName(String sizeName) {
        switch (sizeName) {
            case "small":  return INTERNAL_FONT_SIZE_SMALL;
            case "title":  return INTERNAL_FONT_SIZE_TITLE;
            case "ticket": return INTERNAL_FONT_SIZE_TICKET;
            default:       return INTERNAL_FONT_SIZE;
        }
    }

    private int drawInternalFormattedText(InternalPrinterMethods methods, String text, int initialY) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            return initialY;
        }

        int y = initialY;
        String[] lines = text.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine;
            if (line.trim().isEmpty()) {
                y += INTERNAL_LINE_GAP;
                continue;
            }

            Matcher qrMatcher = Pattern.compile("<qrcode[^>]*>(.*?)</qrcode>", Pattern.CASE_INSENSITIVE).matcher(line);
            if (qrMatcher.find()) {
                String qrData = qrMatcher.group(1).trim();
                if (!qrData.isEmpty()) {
                    int size = getInternalQrSize(line);
                    String align = getInternalAlignment(line);
                    y += getInternalQrTopGap(line);
                    int x = getInternalQrX(line, size, align);
                    int height = methods.drawBarcode(qrData, x, y, INTERNAL_BARCODE_QRCODE, 5, size, INTERNAL_NO_ROTATE);
                    y += Math.max(height, 0) + getInternalQrGap(line);
                }
                continue;
            }

            Matcher imageMatcher = Pattern.compile("<img[^>]*>(.*?)</img>", Pattern.CASE_INSENSITIVE).matcher(line);
            if (imageMatcher.find()) {
                Bitmap image = decodeInternalImage(imageMatcher.group(1));
                if (image != null) {
                    Bitmap trimmed = trimInternalBitmap(image);
                    Bitmap scaled = scaleInternalBitmap(trimmed, INTERNAL_LOGO_MAX_WIDTH);
                    int x = Math.max((INTERNAL_PAGE_WIDTH - scaled.getWidth()) / 2, 0);
                    int height = methods.drawBitmap(scaled, x, y);
                    y += Math.max(height, scaled.getHeight()) + INTERNAL_LOGO_GAP;
                }
                continue;
            }

            String align = getInternalAlignment(line);
            line = stripInternalAlignmentTag(line);

            String lowerLine = line.toLowerCase();
            boolean bold = lowerLine.contains("<b>") || lowerLine.contains("<strong>") || hasInternalEscPosBold(line);
            int fontSize = getInternalFontSize(line);

            String printable = stripInternalEscPosCommands(line)
                .replaceAll("<[^>]*>", "")
                .replaceAll("\\[(?![LCRlcr]\\])[^\\]]*\\]", "")
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "")
                .trim();

            if (printable.isEmpty()) {
                y += INTERNAL_LINE_GAP;
                continue;
            }

            ArrayList<String> printableLines = wrapInternalText(printable, fontSize);
            for (String printableLine : printableLines) {
                int x = getInternalApproximateX(printableLine, align, fontSize);
                int height = methods.drawText(printableLine, x, y, "", fontSize, bold, false, INTERNAL_NO_ROTATE);
                y += Math.max(height, fontSize + INTERNAL_LINE_GAP);
            }
        }

        return y;
    }

    private Bitmap decodeInternalImage(String imageData) {
        if (imageData == null || imageData.trim().isEmpty()) {
            return null;
        }

        String base64Data = imageData.trim();
        int commaIndex = base64Data.indexOf(",");
        if (commaIndex >= 0) {
            base64Data = base64Data.substring(commaIndex + 1);
        }

        try {
            byte[] decoded = Base64.decode(base64Data, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
        } catch (Exception error) {
            android.util.Log.w("ThermalPrinter", "Unable to decode internal printer image: " + error.getMessage());
            return null;
        }
    }

    private Bitmap scaleInternalBitmap(Bitmap bitmap, int maxWidth) {
        if (bitmap.getWidth() <= maxWidth) {
            return bitmap;
        }

        int width = maxWidth;
        int height = Math.max((bitmap.getHeight() * width) / bitmap.getWidth(), 1);
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }

    private Bitmap trimInternalBitmap(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                int alpha = (pixel >> 24) & 0xFF;
                int red = (pixel >> 16) & 0xFF;
                int green = (pixel >> 8) & 0xFF;
                int blue = pixel & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                if (red >= INTERNAL_WHITE_THRESHOLD && green >= INTERNAL_WHITE_THRESHOLD && blue >= INTERNAL_WHITE_THRESHOLD) {
                    continue;
                }

                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }

        if (right < left || bottom < top) {
            return bitmap;
        }

        return Bitmap.createBitmap(bitmap, left, top, (right - left) + 1, (bottom - top) + 1);
    }

    private int getInternalApproximateX(String text, String align, int fontSize) {
        Paint paint = createInternalPaint(fontSize);
        int textWidth = Math.min(Math.round(paint.measureText(text)), getInternalTextMaxWidth());
        return getInternalAlignedX(textWidth, align);
    }

    private int getInternalAlignedX(int contentWidth, String align) {
        int maxWidth = getInternalTextMaxWidth();
        int safeWidth = Math.min(contentWidth, maxWidth);
        if ("right".equals(align)) {
            return Math.max(INTERNAL_PAGE_WIDTH - INTERNAL_TEXT_HORIZONTAL_PADDING - safeWidth, INTERNAL_TEXT_HORIZONTAL_PADDING);
        }
        if ("center".equals(align)) {
            return Math.max((INTERNAL_PAGE_WIDTH - safeWidth) / 2, INTERNAL_TEXT_HORIZONTAL_PADDING);
        }
        return INTERNAL_TEXT_HORIZONTAL_PADDING;
    }

    private int getInternalTextMaxWidth() {
        return INTERNAL_PAGE_WIDTH - (INTERNAL_TEXT_HORIZONTAL_PADDING * 2);
    }

    private Paint createInternalPaint(int fontSize) {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(fontSize);
        return paint;
    }

    private ArrayList<String> wrapInternalText(String text, int fontSize) {
        ArrayList<String> lines = new ArrayList<>();
        Paint paint = createInternalPaint(fontSize);
        int maxWidth = getInternalTextMaxWidth();
        String[] words = text.split("\\s+");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            if (word == null || word.isEmpty()) {
                continue;
            }

            if (paint.measureText(word) > maxWidth) {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                    currentLine.setLength(0);
                }
                splitInternalLongWord(lines, word, paint, maxWidth);
                continue;
            }

            String candidate = currentLine.length() == 0 ? word : currentLine + " " + word;
            if (paint.measureText(candidate) <= maxWidth) {
                currentLine.setLength(0);
                currentLine.append(candidate);
            } else {
                lines.add(currentLine.toString());
                currentLine.setLength(0);
                currentLine.append(word);
            }
        }

        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        if (lines.isEmpty() && text != null && !text.isEmpty()) {
            lines.add(text);
        }

        return lines;
    }

    private void splitInternalLongWord(ArrayList<String> lines, String word, Paint paint, int maxWidth) {
        StringBuilder currentPart = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            String candidate = currentPart.toString() + word.charAt(i);
            if (currentPart.length() > 0 && paint.measureText(candidate) > maxWidth) {
                lines.add(currentPart.toString());
                currentPart.setLength(0);
            }
            currentPart.append(word.charAt(i));
        }
        if (currentPart.length() > 0) {
            lines.add(currentPart.toString());
        }
    }

    private int getInternalQrX(String line, int size, String align) {
        Matcher xMatcher = Pattern.compile("<qrcode[^>]*x=(?:\'|\")?(\\d+)(?:\'|\")?[^>]*>", Pattern.CASE_INSENSITIVE).matcher(line);
        if (xMatcher.find()) {
            try {
                int requestedX = Integer.parseInt(xMatcher.group(1));
                return Math.max(0, Math.min(requestedX, INTERNAL_QR_X_MAX));
            } catch (Exception ignored) {
                return getInternalAlignedX(size, align);
            }
        }

        return getInternalAlignedX(size, align);
    }

    private int getInternalQrGap(String line) {
        Matcher gapMatcher = Pattern.compile("<qrcode[^>]*gap=(?:\'|\")?(\\d+)(?:\'|\")?[^>]*>", Pattern.CASE_INSENSITIVE).matcher(line);
        if (gapMatcher.find()) {
            try {
                return Math.max(0, Integer.parseInt(gapMatcher.group(1)));
            } catch (Exception ignored) {
                return INTERNAL_LINE_GAP;
            }
        }

        return INTERNAL_LINE_GAP;
    }

    private int getInternalQrTopGap(String line) {
        Matcher topGapMatcher = Pattern.compile("<qrcode[^>]*topgap=(?:\'|\")?(\\d+)(?:\'|\")?[^>]*>", Pattern.CASE_INSENSITIVE).matcher(line);
        if (topGapMatcher.find()) {
            try {
                return Math.max(0, Integer.parseInt(topGapMatcher.group(1)));
            } catch (Exception ignored) {
                return 0;
            }
        }

        return 0;
    }

    private int getInternalQrSize(String line) {
        Matcher sizeMatcher = Pattern.compile("<qrcode[^>]*size=(?:\'|\\\")?(\\d+)(?:\'|\\\")?[^>]*>", Pattern.CASE_INSENSITIVE).matcher(line);
        if (sizeMatcher.find()) {
            try {
                int requestedSize = Integer.parseInt(sizeMatcher.group(1));
                return Math.max(INTERNAL_QR_MIN_SIZE, Math.min(requestedSize * 11, INTERNAL_QR_MAX_SIZE));
            } catch (Exception ignored) {
                return INTERNAL_QR_DEFAULT_SIZE;
            }
        }
        return INTERNAL_QR_DEFAULT_SIZE;
    }

    private String getInternalAlignment(String line) {
        String align = "left";
        Matcher alignMatcher = Pattern.compile("^\\s*\\[(L|C|R)\\]", Pattern.CASE_INSENSITIVE).matcher(line);
        if (alignMatcher.find()) {
            String tag = alignMatcher.group(1).toUpperCase();
            align = "C".equals(tag) ? "center" : "R".equals(tag) ? "right" : "left";
        }

        for (int i = 0; i + 2 < line.length(); i++) {
            if (line.charAt(i) == 0x1B && line.charAt(i + 1) == 'a') {
                char value = line.charAt(i + 2);
                if (value == 0x01 || value == '1') {
                    align = "center";
                } else if (value == 0x02 || value == '2') {
                    align = "right";
                } else if (value == 0x00 || value == '0') {
                    align = "left";
                }
            }
        }

        return align;
    }

    private String stripInternalAlignmentTag(String line) {
        Matcher alignMatcher = Pattern.compile("^\\s*\\[(L|C|R)\\]", Pattern.CASE_INSENSITIVE).matcher(line);
        if (alignMatcher.find()) {
            return line.substring(alignMatcher.end());
        }
        return line;
    }

    private boolean hasInternalEscPosBold(String line) {
        for (int i = 0; i + 2 < line.length(); i++) {
            if (line.charAt(i) == 0x1B && line.charAt(i + 1) == 'E') {
                char value = line.charAt(i + 2);
                if (value == 0x01 || value == '1') {
                    return true;
                }
            }
        }
        return false;
    }

    private int getInternalFontSize(String line) {
        String lowerLine = line.toLowerCase();
        if (lowerLine.contains("size='tall'") || lowerLine.contains("size=\"tall\"")) {
            return INTERNAL_FONT_SIZE_TICKET;
        }
        if (lowerLine.contains("size='wide'") || lowerLine.contains("size=\"wide\"")) {
            return INTERNAL_FONT_SIZE_TITLE;
        }
        if (lowerLine.contains("size='small'") || lowerLine.contains("size=\"small\"")) {
            return INTERNAL_FONT_SIZE_SMALL;
        }

        int fontSize = INTERNAL_FONT_SIZE;
        for (int i = 0; i + 2 < line.length(); i++) {
            if (line.charAt(i) == 0x1D && line.charAt(i + 1) == '!') {
                int value = line.charAt(i + 2);
                if (value >= 0x55) {
                    fontSize = Math.max(fontSize, INTERNAL_FONT_SIZE_TICKET);
                } else if ((value & 0x11) == 0x11 || value >= 0x11) {
                    fontSize = Math.max(fontSize, INTERNAL_FONT_SIZE_TITLE);
                }
            }
        }
        return fontSize;
    }

    private String stripInternalEscPosCommands(String line) {
        StringBuilder printable = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == 0x1B && i + 2 < line.length()) {
                char command = line.charAt(i + 1);
                if (command == 'a' || command == 'E') {
                    i += 2;
                    continue;
                }
            }
            if (current == 0x1D && i + 2 < line.length() && line.charAt(i + 1) == '!') {
                i += 2;
                continue;
            }
            printable.append(current);
        }
        return printable.toString();
    }

    private static class InternalPrinterMethods {
        private final Object printerManager;
        private final Method open;
        private final Method close;
        private final Method setupPage;
        private final Method clearPage;
        private final Method drawText;
        private final Method drawBitmap;
        private final Method drawBarcode;
        private final Method paperFeed;
        private final Method printPage;

        InternalPrinterMethods(Object printerManager) throws NoSuchMethodException {
            this.printerManager = printerManager;
            Class<?> managerClass = printerManager.getClass();
            open = managerClass.getMethod("open");
            close = managerClass.getMethod("close");
            setupPage = managerClass.getMethod("setupPage", int.class, int.class);
            clearPage = managerClass.getMethod("clearPage");
            drawText = managerClass.getMethod(
                "drawText",
                String.class,
                int.class,
                int.class,
                String.class,
                int.class,
                boolean.class,
                boolean.class,
                int.class
            );
            drawBitmap = managerClass.getMethod("drawBitmap", Bitmap.class, int.class, int.class);
            drawBarcode = managerClass.getMethod(
                "drawBarcode",
                String.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class
            );
            paperFeed = managerClass.getMethod("paperFeed", int.class);
            printPage = managerClass.getMethod("printPage", int.class);
        }

        int open() throws Exception {
            return (Integer) open.invoke(printerManager);
        }

        void close() throws Exception {
            close.invoke(printerManager);
        }

        void setupPage(int width, int height) throws Exception {
            setupPage.invoke(printerManager, width, height);
        }

        void clearPage() throws Exception {
            clearPage.invoke(printerManager);
        }

        int drawText(String text, int x, int y, String font, int size, boolean bold, boolean italic, int rotate) throws Exception {
            return (Integer) drawText.invoke(printerManager, text, x, y, font, size, bold, italic, rotate);
        }

        int drawBitmap(Bitmap bitmap, int x, int y) throws Exception {
            return (Integer) drawBitmap.invoke(printerManager, bitmap, x, y);
        }

        int drawBarcode(String data, int x, int y, int type, int width, int height, int rotate) throws Exception {
            return (Integer) drawBarcode.invoke(printerManager, data, x, y, type, width, height, rotate);
        }

        void paperFeed(int level) throws Exception {
            paperFeed.invoke(printerManager, level);
        }

        int printPage(int rotate) throws Exception {
            return (Integer) printPage.invoke(printerManager, rotate);
        }
    }

    private EscPosPrinter getPrinter(CallbackContext callbackContext, JSONObject data) throws JSONException {
        DeviceConnection deviceConnection = this.getPrinterConnection(callbackContext, data);
        if (deviceConnection == null) {
            throw new JSONException("Device not found");
        }

        EscPosCharsetEncoding charsetEncoding = null;
        try {
            if (data.optJSONObject("charsetEncoding") != null) {
                JSONObject charsetEncodingData = data.optJSONObject("charsetEncoding");
                if (charsetEncodingData == null) {
                    charsetEncodingData = new JSONObject();
                }
                charsetEncoding = new EscPosCharsetEncoding(
                    charsetEncodingData.optString("charsetName", "windows-1252"),
                    charsetEncodingData.optInt("charsetId", 16)
                );
            }
        } catch (Exception exception) {
            final String errorMsg = exception.getMessage();
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", errorMsg);
            }}));
            throw new JSONException(errorMsg);
        }

        try {
            return new EscPosPrinter(
                deviceConnection,
                data.optInt("printerDpi", 203),
                (float) data.optDouble("printerWidthMM", 48f),
                data.optInt("printerNbrCharactersPerLine", 32),
                charsetEncoding
            );
        } catch (Exception e) {
            final String errorMsg = e.getMessage();
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", errorMsg);
            }}));
            throw new JSONException(errorMsg);
        }
    }

    private DeviceConnection getPrinterConnection(CallbackContext callbackContext, JSONObject data) throws JSONException {
        final String type = data.getString("type");
        final String id = data.optString("id", "");
        String hashKey = buildConnectionKey(data);
        
        DeviceConnection deviceConnection = this.getDevice(callbackContext, data);
        
        if (deviceConnection == null) {
            final String errorMsg = "Device not found or not connected!" + 
                (type.equals("usb") ? " USB device may have been disconnected. Try reconnecting the device or restarting the app." : "");
            android.util.Log.e("ThermalPrinter", errorMsg + " type=" + type + ", id=" + id);
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", errorMsg);
                put("type", type);
                put("id", id);
            }}));
        }
        // Thread-safe: cache the new connection
        if (deviceConnection != null) {
            synchronized (connections) {
                if (!this.connections.containsKey(hashKey)) {
                    this.connections.put(hashKey, deviceConnection);
                    android.util.Log.d("ThermalPrinter", "Cached new connection with key: " + hashKey);
                }
            }
        }
        return deviceConnection;
    }

    private boolean checkBluetooth(CallbackContext callbackContext) {
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", "Device doesn't support Bluetooth!");
            }}));
            return false;
        } else if (!mBluetoothAdapter.isEnabled()) {
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", "Device not enabled Bluetooth!");
            }}));
            return false;
        }
        return true;
    }
}
