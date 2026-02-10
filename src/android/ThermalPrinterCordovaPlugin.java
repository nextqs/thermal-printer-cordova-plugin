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
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

public class ThermalPrinterCordovaPlugin extends CordovaPlugin {
    private final HashMap<String, DeviceConnection> connections = new HashMap<>();
    private BroadcastReceiver usbDetachReceiver;
    private boolean isUsbReceiverRegistered = false;
    
    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        registerUsbDetachReceiver();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterUsbDetachReceiver();
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
        
        // Clear ALL cached USB connections (deviceId changes after reconnect)
        // Thread-safe: synchronize access to connections HashMap
        synchronized (connections) {
            ArrayList<String> keysToRemove = new ArrayList<>();
            
            for (String key : connections.keySet()) {
                if (key.startsWith("usb-")) {
                    keysToRemove.add(key);
                }
            }
            
            for (String key : keysToRemove) {
                DeviceConnection connection = connections.get(key);
                if (connection != null) {
                    try {
                        connection.disconnect();
                        android.util.Log.i("ThermalPrinter", "Disconnected and removed cached USB connection: " + key);
                    } catch (Exception e) {
                        android.util.Log.e("ThermalPrinter", "Error disconnecting: " + e.getMessage());
                    }
                }
                connections.remove(key);
            }
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
                }
            } catch (JSONException exception) {
                callbackContext.error(exception.getMessage());
            }
        });

        return true;
    }

    private void bitmapToHexadecimalString(CallbackContext callbackContext, JSONObject data) throws JSONException {
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
            }
        } else {
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
        }

        callbackContext.success(printers);
    }

    private void printFormattedText(CallbackContext callbackContext, String action, JSONObject data) throws JSONException {
        EscPosPrinter printer = this.getPrinter(callbackContext, data);
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
            callbackContext.success();
        } catch (EscPosConnectionException e) {
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "EscPosConnectionException occurred";
            android.util.Log.e("ThermalPrinter", "Print connection error: " + errorMsg, e);
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", "Connection error: " + errorMsg);
                put("type", "CONNECTION_ERROR");
            }}));
        } catch (Exception e) {
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error during print: " + e.getClass().getSimpleName();
            android.util.Log.e("ThermalPrinter", "Print error: " + errorMsg, e);
            callbackContext.error(new JSONObject(new HashMap<String, Object>() {{
                put("error", errorMsg);
                put("type", "PRINT_ERROR");
            }}));
        }
    }

    private void getEncoding(CallbackContext callbackContext, JSONObject data) throws JSONException {
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
        EscPosPrinter printer = this.getPrinter(callbackContext, data);
        printer.disconnectPrinter();
        callbackContext.success();
    }

    private String buildConnectionKey(JSONObject data) throws JSONException {
        String type = data.getString("type");
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
            
            int vendorId = cachedDevice.getVendorId();
            int productId = cachedDevice.getProductId();
            String serial = "";
            try { 
                serial = cachedDevice.getSerialNumber(); 
            } catch (Exception ignored) {}
            
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            for (UsbDevice device : deviceList.values()) {
                // Match by vendorId + productId (deviceId changes after reconnect!)
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
