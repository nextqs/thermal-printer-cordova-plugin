package de.paystory.thermal_printer;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbEndpoint;
import android.content.Context;
import android.util.Log;
import android.os.SystemClock;
import androidx.appcompat.app.AppCompatActivity;
import com.dantsu.escposprinter.connection.DeviceConnection;
import com.dantsu.escposprinter.connection.usb.UsbConnection;
import com.dantsu.escposprinter.EscPosPrinter;
import com.dantsu.escposprinter.exceptions.EscPosConnectionException;
import com.dantsu.escposprinter.exceptions.EscPosParserException;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JVM checks for the protocol boundary; physical USB/OTG validation is documented in README.md. */
public class UsbPrinterStatusTest {
    private static final Class<?> PLUGIN = ThermalPrinterCordovaPlugin.class;
    private static int checks;

    /**
     * Read from the production constant instead of repeating its value: the per-transfer budget is tuned
     * against real printers, and the invariant under test is that every transfer is bounded by it, not
     * that it holds any particular number.
     */
    private static final int TRANSFER_TIMEOUT_MS = transferTimeoutMs();
    /** Advance per clock read: small next to the transfer budget, large enough to expire it quickly. */
    private static final int CLOCK_STEP_MS = 5;
    private static final long CLOCK_START_MS = 100L;
    /** Shared so every query starts from the same instant; see startClock(). */
    private static final AtomicLong now = new AtomicLong(CLOCK_START_MS);

    /** Rewind before each query: deadlines in these checks are absolute values near CLOCK_START_MS. */
    private static void startClock() {
        now.set(CLOCK_START_MS);
    }

    private static int transferTimeoutMs() {
        try {
            Field field = PLUGIN.getDeclaredField("USB_STATUS_TRANSFER_TIMEOUT_MS");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("USB_STATUS_TRANSFER_TIMEOUT_MS is missing", e);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = PLUGIN.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        try (MockedStatic<SystemClock> clock = mockStatic(SystemClock.class);
             MockedStatic<Log> log = mockStatic(Log.class)) {
            // Monotonic, not frozen: the read path polls the IN endpoint until its slice of the budget
            // runs out, so a constant clock never lets a deadline expire and the poll loop never ends.
            // Starting at 100 keeps the expired-deadline check below (deadline == 100) expired.
            clock.when(SystemClock::elapsedRealtime).thenAnswer(call -> now.getAndAdd(CLOCK_STEP_MS));
            ThermalPrinterCordovaPlugin plugin = new ThermalPrinterCordovaPlugin();
            Class<?>[] sensorTypes = { int.class, int.class, boolean.class };
            for (int value : new int[] { 0x12, 0x1E, 0x72, 0x7E }) {
                check(invoke(null, "decodePaperSensor", sensorTypes, value, 0x60, true)
                    .equals((value & 0x60) == 0), "paper presence: " + value);
                check(invoke(null, "decodePaperSensor", sensorTypes, value, 0x0C, false)
                    .equals((value & 0x0C) == 0x0C), "near end: " + value);
            }
            for (int value : new int[] { 0x32, 0x52 }) {
                check(invoke(null, "decodePaperSensor", sensorTypes, value, 0x60, true) == JSONObject.NULL,
                    "mixed paper bits must remain unknown");
            }
            for (int value : new int[] { 0x16, 0x1A }) {
                check(invoke(null, "decodePaperSensor", sensorTypes, value, 0x0C, false) == JSONObject.NULL,
                    "mixed near-end bits must remain unknown");
            }

            for (int value = 0; value < 256; value++) {
                JSONObject result = unknown(plugin);
                Integer reply = query(plugin, result, new byte[] { (byte) value }, 1, 3, 600L);
                boolean valid = (value & 0x93) == 0x12;
                check(valid == (reply != null), "fixed-bit validation: " + value);
                check(result.getJSONObject("raw").getJSONArray("paper").getInt(0) == value,
                    "raw byte must be unsigned: " + value);
                check(valid ? result.getBoolean("supported") : result.isNull("supported"),
                    "support must only be inferred from a valid response");
            }

            JSONObject result = unknown(plugin);
            check(query(plugin, result, new byte[0], -1, 3, 600L) == null, "timeout returns no sensor data");
            check(result.getString("reason").equals("timeout"), "timeout reason");
            check(result.isNull("supported") && result.isNull("paperPresent"), "timeout is unknown");

            result = unknown(plugin);
            check(query(plugin, result, new byte[] { 0x12, 0x16 }, 2, 3, 600L) == null,
                "multiple replies cannot be assigned to a sensor");
            check(result.getString("reason").equals("invalid_response"), "invalid response reason");
            check(result.getJSONObject("raw").getJSONArray("paper").length() == 2, "preserve malformed bytes");

            result = unknown(plugin);
            check(query(plugin, result, new byte[0], -1, 2, 600L) == null, "short writes stop the query");
            check(result.getString("reason").equals("write_timeout"), "short write reason");

            result = unknown(plugin);
            check(query(plugin, result, new byte[0], -1, 3, 100L) == null, "expired deadline skips USB");
            check(result.getString("reason").equals("timeout"), "deadline reason");
            cacheChecks();
            printFailureChecks();
            integrationChecks();
            System.out.println("USB status checks passed: " + checks);
        }
    }

    private static HashMap<String, UsbDevice> bus(UsbDevice... devices) {
        HashMap<String, UsbDevice> list = new HashMap<>();
        for (UsbDevice device : devices) {
            list.put(device.getDeviceName(), device);
        }
        return list;
    }

    private static UsbDevice usbDevice(int deviceId, String name) {
        UsbDevice device = mock(UsbDevice.class);
        when(device.getDeviceId()).thenReturn(deviceId);
        when(device.getDeviceName()).thenReturn(name);
        when(device.getVendorId()).thenReturn(1208);
        when(device.getProductId()).thenReturn(3623);
        return device;
    }

    /**
     * The two cache rules a regression would turn back into a lost ticket: reuse a cached connection only
     * while the device keeps the same enumeration, and never drop an entry that another thread replaced
     * while it was being validated outside the lock.
     */
    private static void cacheChecks() throws Exception {
        ThermalPrinterCordovaPlugin plugin = new ThermalPrinterCordovaPlugin();
        UsbManager manager = mock(UsbManager.class);
        plugin.cordova = mock(CordovaInterface.class);
        AppCompatActivity activity = mock(AppCompatActivity.class);
        when(plugin.cordova.getActivity()).thenReturn(activity);
        when(activity.getSystemService(Context.USB_SERVICE)).thenReturn(manager);

        UsbDevice cached = usbDevice(123, "/dev/bus/usb/001/002");
        Class<?>[] presence = { UsbDevice.class };

        // Build every mock before stubbing getDeviceList: nesting when() inside thenReturn() leaves
        // Mockito with an unfinished stub.
        HashMap<String, UsbDevice> sameBus = bus(usbDevice(123, "/dev/bus/usb/001/002"));
        HashMap<String, UsbDevice> reEnumeratedBus = bus(usbDevice(456, "/dev/bus/usb/001/005"));

        when(manager.getDeviceList()).thenReturn(sameBus);
        check((Boolean) invoke(plugin, "isUsbDeviceStillPresent", presence, cached),
            "same enumeration may be reused");

        // The fix itself: vendor/product/serial still match after a re-enumeration, the file descriptor
        // does not. Reusing here is what produced "Error during claim USB interface" in the field.
        when(manager.getDeviceList()).thenReturn(reEnumeratedBus);
        check(!(Boolean) invoke(plugin, "isUsbDeviceStillPresent", presence, cached),
            "re-enumerated device must not be reused");

        when(manager.getDeviceList()).thenReturn(new HashMap<String, UsbDevice>());
        check(!(Boolean) invoke(plugin, "isUsbDeviceStillPresent", presence, cached),
            "absent device must not be reused");

        when(activity.getSystemService(Context.USB_SERVICE)).thenReturn(null);
        check(!(Boolean) invoke(plugin, "isUsbDeviceStillPresent", presence, cached),
            "unavailable UsbManager must not be reused");
        when(activity.getSystemService(Context.USB_SERVICE)).thenReturn(manager);

        Field field = PLUGIN.getDeclaredField("connections");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        HashMap<String, DeviceConnection> cache = (HashMap<String, DeviceConnection>) field.get(plugin);
        String key = "usb-1208-3623";
        ArrayList<String> keys = new ArrayList<>();
        keys.add(key);
        Class<?>[] removeMany = { ArrayList.class, HashMap.class, String.class };
        Class<?>[] removeOne = { String.class, DeviceConnection.class, String.class };

        UsbConnection validated = mock(UsbConnection.class);
        HashMap<String, DeviceConnection> expected = new HashMap<>();
        expected.put(key, validated);

        // Validation happens outside the lock, so the entry may already belong to a newer connection.
        UsbConnection replacement = mock(UsbConnection.class);
        cache.clear();
        cache.put(key, replacement);
        invoke(plugin, "removeUsbConnections", removeMany, keys, expected, "check");
        check(cache.get(key) == replacement, "a connection opened during validation is kept");
        verify(replacement, never()).disconnect();

        UsbConnection stale = mock(UsbConnection.class);
        HashMap<String, DeviceConnection> staleExpected = new HashMap<>();
        staleExpected.put(key, stale);
        cache.clear();
        cache.put(key, stale);
        invoke(plugin, "removeUsbConnections", removeMany, keys, staleExpected, "check");
        check(cache.isEmpty(), "the validated connection is removed");
        verify(stale).disconnect();

        // The generic helper also supports unconditional removal when explicitly requested.
        UsbConnection anything = mock(UsbConnection.class);
        cache.clear();
        cache.put(key, anything);
        invoke(plugin, "removeUsbConnections", removeMany, keys, null, "check");
        check(cache.isEmpty(), "a null expectation drops whatever is cached");
        verify(anything).disconnect();

        // The print path goes through the single-key wrapper and must honour the same rule.
        UsbConnection printReplacement = mock(UsbConnection.class);
        cache.clear();
        cache.put(key, printReplacement);
        invoke(plugin, "removeUsbConnection", removeOne, key, validated, "check");
        check(cache.get(key) == printReplacement, "the print path keeps a replaced connection too");
        verify(printReplacement, never()).disconnect();

        UsbConnection printStale = mock(UsbConnection.class);
        cache.clear();
        cache.put(key, printStale);
        invoke(plugin, "removeUsbConnection", removeOne, key, printStale, "check");
        check(cache.isEmpty(), "the print path removes the connection it validated");
        verify(printStale).disconnect();
        cache.clear();
    }

    private static JSONObject printData() throws Exception {
        return new JSONObject().put("type", "usb").put("id", 123)
            .put("vendorId", 1208).put("productId", 3623).put("text", "ticket");
    }

    private static void printFailureChecks() throws Exception {
        for (String action : new String[] { "printFormattedText", "printFormattedTextAndCut" }) {
            for (boolean transportError : new boolean[] { true, false }) {
                Fixture f = new Fixture(0x12, 0x12);
                UsbConnection unrelated = mock(UsbConnection.class);
                UsbConnection replacement = mock(UsbConnection.class);
                f.cache.put("usb-other", unrelated);
                Exception failure = transportError ? new EscPosConnectionException("write failed")
                    : new EscPosParserException("render failed");
                try (MockedConstruction<EscPosPrinter> printers = mockConstruction(EscPosPrinter.class,
                    (printer, context) -> {
                        check(context.arguments().get(0) == f.writer, "retain the actual writer used by the print");
                        org.mockito.stubbing.Answer<EscPosPrinter> fail = call -> {
                            // Simulate a cache replacement while this job still holds the old writer.
                            f.cache.put("usb-1208-3623", replacement);
                            throw failure;
                        };
                        when(printer.printFormattedText(anyString(), anyInt())).thenAnswer(fail);
                        when(printer.printFormattedTextAndCut(anyString(), anyInt())).thenAnswer(fail);
                    })) {
                    CallbackContext callback = mock(CallbackContext.class);
                    f.plugin.execute(action, new JSONArray().put(printData()), callback);
                    ArgumentCaptor<JSONObject> error = ArgumentCaptor.forClass(JSONObject.class);
                    verify(callback).error(error.capture());
                    check(error.getValue().getString("type").equals(transportError ? "CONNECTION_ERROR" : "PRINT_ERROR"),
                        "print error contract preserved");
                    verifyNoMoreInteractions(callback);
                    check(f.cache.get("usb-other") == unrelated, "failed print preserves the other printer");
                    check(f.cache.get("usb-1208-3623") == replacement, "failed print preserves its replacement");
                    check(!f.cache.containsKey("usb-123"), "aliases of the failed writer are removed");
                    verify(f.writer).disconnect();
                    verify(unrelated, never()).disconnect();
                    verify(replacement, never()).disconnect();
                }
            }
        }

        Fixture invalid = new Fixture(0x12, 0x12);
        try (MockedConstruction<EscPosPrinter> printers = mockConstruction(EscPosPrinter.class)) {
            JSONObject data = printData();
            data.remove("text");
            CallbackContext rejected = mock(CallbackContext.class);
            invalid.plugin.execute("printFormattedText", new JSONArray().put(data), rejected);
            verify(rejected).error(any(JSONObject.class));
            verifyNoMoreInteractions(rejected);
            verify(printers.constructed().get(0), never()).printFormattedText(anyString(), anyInt());
            check(invalid.cache.get("usb-1208-3623") == invalid.writer && invalid.cache.get("usb-123") == invalid.writer,
                "invalid payload before rendering preserves the writer and aliases");
            verify(invalid.writer, never()).disconnect();
            CallbackContext next = mock(CallbackContext.class);
            invalid.plugin.execute("printFormattedText", new JSONArray().put(printData()), next);
            verify(next).success();
            verifyNoMoreInteractions(next);
            verify(printers.constructed().get(1)).printFormattedText("ticket", 20);
        }

        // Exercise the real constructor: connection setup exceptions are wrapped by getPrinter.
        Fixture setup = new Fixture(0x12, 0x12);
        UsbConnection unrelated = mock(UsbConnection.class);
        setup.cache.put("usb-other", unrelated);
        when(setup.writer.connect()).thenThrow(new EscPosConnectionException("open failed"));
        setup.plugin.execute("printFormattedText", new JSONArray().put(printData()), mock(CallbackContext.class));
        check(setup.cache.size() == 1 && setup.cache.get("usb-other") == unrelated,
            "connection setup failure removes only the failed writer and its aliases");
        verify(setup.writer).disconnect();
        verify(unrelated, never()).disconnect();
    }

    private static void integrationChecks() throws Exception {
        Fixture f = new Fixture(0x12, 0x12);
        JSONObject result = f.status();
        check(result.getBoolean("paperPresent") && !result.getBoolean("paperNearEnd")
            && !result.getBoolean("coverOpen") && result.isNull("reason"), "loaded/closed snapshot");
        check(f.commands.equals(Arrays.asList(4, 2)), "paper then offline query order");
        check(f.cache.isEmpty(), "all aliases for selected device removed");
        verify(f.writer, times(2)).disconnect();
        verify(f.handle).claimInterface(f.usbInterface, false);
        verify(f.handle).releaseInterface(f.usbInterface);
        verify(f.handle).close();

        f = new Fixture(0x7E, 0x16);
        result = f.status();
        check(!result.getBoolean("paperPresent") && result.getBoolean("paperNearEnd")
            && result.getBoolean("coverOpen"), "empty/near-end/open snapshot");

        f = new Fixture(-1, 0x16);
        result = f.status();
        check(result.isNull("paperPresent") && result.isNull("coverOpen") && result.isNull("supported"),
            "missing paper response is unknown");
        check(f.commands.equals(Arrays.asList(4)), "do not send cover request after paper timeout");
        verify(f.handle).close();

        f = new Fixture(0x12, -1);
        result = f.status();
        check(result.getBoolean("paperPresent") && result.isNull("coverOpen")
            && result.getBoolean("supported") && result.getString("reason").equals("timeout"), "partial reply");

        f = new Fixture(0x02, 0x16);
        result = f.status();
        check(result.getString("reason").equals("invalid_response") && f.commands.size() == 1,
            "wrong fixed bit rejects paper and skips cover");

        f = new Fixture(0x32, 0x12);
        result = f.status();
        check(result.isNull("paperPresent") && !result.getBoolean("coverOpen")
            && result.getString("reason").equals("invalid_response"), "mixed bits preserve other sensor data");

        f = new Fixture(0x12, 0x12);
        when(f.usbInterface.getEndpointCount()).thenReturn(1);
        result = f.status();
        check(!result.getBoolean("supported") && result.getString("reason").equals("no_status_endpoint"),
            "no IN endpoint means unsupported");
        verify(f.manager, never()).openDevice(any());
        verify(f.writer, never()).disconnect();

        f = new Fixture(0x12, 0x12);
        when(f.manager.hasPermission(f.device)).thenReturn(false);
        check(f.status().getString("reason").equals("permission_required"), "permission is not requested implicitly");
        verify(f.manager, never()).openDevice(any());

        f = new Fixture(0x12, 0x12);
        // Each open returns a distinct descriptor. A failed claim must close that descriptor exactly
        // once; retrying must never reuse a closed handle or release an interface it did not claim.
        UsbDeviceConnection second = mock(UsbDeviceConnection.class);
        UsbDeviceConnection third = mock(UsbDeviceConnection.class);
        when(f.manager.openDevice(f.device)).thenReturn(f.handle, second, third);
        when(f.handle.claimInterface(f.usbInterface, false)).thenReturn(false);
        check(f.status().getString("reason").equals("interface_unavailable"), "failed claims remain unknown");
        verify(f.manager, times(3)).openDevice(f.device);
        verify(f.handle).claimInterface(f.usbInterface, false);
        verify(second).claimInterface(f.usbInterface, false);
        verify(third).claimInterface(f.usbInterface, true);
        for (UsbDeviceConnection attempt : Arrays.asList(f.handle, second, third)) {
            verify(attempt, never()).releaseInterface(any());
            verify(attempt).close();
        }

        f = new Fixture(0x12, 0x12);
        when(f.handle.bulkTransfer(eq(f.output), any(byte[].class), anyInt(), anyInt()))
            .thenThrow(new IllegalStateException("private USB detail"));
        result = f.status();
        check(result.getString("reason").equals("io_error") && !result.toString().contains("private"),
            "transport exceptions are sanitized");
        verify(f.handle).releaseInterface(f.usbInterface);
        verify(f.handle).close();

        f = new Fixture(0x12, 0x12);
        when(f.handle.bulkTransfer(eq(f.input), any(byte[].class), anyInt(), eq(10))).thenReturn(1);
        check(f.status().getString("reason").equals("input_not_quiet"), "bounded stale/ASB drain");
        check(f.commands.isEmpty(), "no request on a noisy input");
        verify(f.handle, times(4)).bulkTransfer(eq(f.input), any(byte[].class), anyInt(), eq(10));
        verify(f.handle).close();

        f = new Fixture(0x12, 0x12);
        UsbConnection unrelated = mock(UsbConnection.class);
        UsbDevice otherDevice = mock(UsbDevice.class);
        when(unrelated.getDevice()).thenReturn(otherDevice);
        when(otherDevice.getDeviceId()).thenReturn(999);
        f.cache.put("usb-other", unrelated);
        f.status();
        check(f.cache.get("usb-other") == unrelated, "unrelated cached printer is kept");
        verify(unrelated, never()).disconnect();

        f = new Fixture(0x12, 0x12);
        when(f.manager.getDeviceList()).thenReturn(new HashMap<>());
        check(f.status().getString("reason").equals("device_not_found"), "absent printer is unknown");
        verify(f.manager, never()).openDevice(any());

        // Re-enumeration must never reuse the old writer, even when vendor/product match.
        f = new Fixture(0x12, 0x12);
        f.cache.remove("usb-123");
        UsbDevice reattached = mock(UsbDevice.class);
        when(reattached.getDeviceId()).thenReturn(456);
        when(reattached.getDeviceName()).thenReturn("/dev/bus/usb/new");
        when(reattached.getVendorId()).thenReturn(1208);
        when(reattached.getProductId()).thenReturn(3623);
        when(reattached.getInterfaceCount()).thenReturn(1);
        when(reattached.getInterface(0)).thenReturn(f.usbInterface);
        HashMap<String, UsbDevice> reattachedDevices = new HashMap<>();
        reattachedDevices.put(reattached.getDeviceName(), reattached);
        when(f.manager.getDeviceList()).thenReturn(reattachedDevices);
        when(f.manager.hasPermission(reattached)).thenReturn(true);
        when(f.manager.openDevice(reattached)).thenReturn(f.handle);
        check(f.status().getBoolean("paperPresent"), "query resolves new deviceId by stable identifiers");
        verify(f.writer).disconnect();
        verify(f.manager, never()).openDevice(f.device);
        verify(f.manager).openDevice(reattached);

        f = new Fixture(0x12, 0x12);
        for (String transport : new String[] { "bluetooth", "tcp", "internal-urovo" }) {
            CallbackContext callback = mock(CallbackContext.class);
            f.plugin.execute("getPrinterStatus", new JSONArray().put(new JSONObject().put("type", transport)), callback);
            ArgumentCaptor<JSONObject> value = ArgumentCaptor.forClass(JSONObject.class);
            verify(callback).success(value.capture());
            check(!value.getValue().getBoolean("supported"), "unsupported transport: " + transport);
            verifyNoMoreInteractions(callback);
        }
        verify(f.manager, never()).openDevice(any());
        CallbackContext invalid = mock(CallbackContext.class);
        f.plugin.execute("getPrinterStatus", new JSONArray(), invalid);
        verify(invalid).error(any(JSONObject.class));
        verifyNoMoreInteractions(invalid);

        f = new Fixture(0x12, 0x12);
        Field lockField = PLUGIN.getDeclaredField("usbOperationLock");
        lockField.setAccessible(true);
        ReentrantLock lock = (ReentrantLock) lockField.get(f.plugin);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                acquired.countDown();
                release.await();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally { lock.unlock(); }
        });
        holder.start();
        acquired.await();
        try {
            check(f.status().getString("reason").equals("busy"), "status never waits behind a USB operation");
            verify(f.manager, never()).openDevice(any());
            verify(f.writer, never()).disconnect();
        } finally {
            release.countDown();
            holder.join();
        }

        final Fixture printing = new Fixture(0x12, 0x12);
        ReentrantLock printingLock = (ReentrantLock) lockField.get(printing.plugin);
        try (MockedConstruction<EscPosPrinter> printers = mockConstruction(EscPosPrinter.class, (printer, context) -> {
            when(printer.printFormattedText(anyString(), anyInt())).thenAnswer(call -> {
                check(printingLock.isHeldByCurrentThread(), "USB lock covers actual print write");
                JSONObject concurrent = CompletableFuture.supplyAsync(printing::status).get(2, TimeUnit.SECONDS);
                check(concurrent.getString("reason").equals("busy"), "status during print returns immediately");
                verify(printing.writer, never()).disconnect();
                verify(printing.manager, never()).openDevice(any());
                return printer;
            });
        })) {
            CallbackContext printed = mock(CallbackContext.class);
            printing.plugin.execute("printFormattedText", new JSONArray().put(new JSONObject()
                .put("type", "usb").put("id", 123).put("vendorId", 1208).put("productId", 3623)
                .put("text", "test")), printed);
            verify(printed).success();
            verifyNoMoreInteractions(printed);
            check(!printingLock.isLocked(), "print releases USB lock");
        }
    }

    private static class Fixture {
        final ThermalPrinterCordovaPlugin plugin = new ThermalPrinterCordovaPlugin();
        final UsbManager manager = mock(UsbManager.class);
        final UsbDevice device = mock(UsbDevice.class);
        final UsbConnection writer = mock(UsbConnection.class);
        final UsbDeviceConnection handle = mock(UsbDeviceConnection.class);
        final UsbInterface usbInterface = mock(UsbInterface.class);
        final UsbEndpoint input = mock(UsbEndpoint.class);
        final UsbEndpoint output = mock(UsbEndpoint.class);
        final HashMap<String, DeviceConnection> cache;
        final List<Integer> commands = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Fixture(int paper, int offline) throws Exception {
            plugin.cordova = mock(CordovaInterface.class);
            AppCompatActivity activity = mock(AppCompatActivity.class);
            when(plugin.cordova.getActivity()).thenReturn(activity);
            when(activity.getSystemService(Context.USB_SERVICE)).thenReturn(manager);
            ExecutorService pool = mock(ExecutorService.class);
            when(plugin.cordova.getThreadPool()).thenReturn(pool);
            doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(pool).execute(any());
            when(device.getDeviceId()).thenReturn(123);
            when(device.getDeviceName()).thenReturn("/dev/bus/usb/test");
            when(device.getVendorId()).thenReturn(1208);
            when(device.getProductId()).thenReturn(3623);
            when(device.getInterfaceCount()).thenReturn(1);
            when(device.getInterface(0)).thenReturn(usbInterface);
            when(usbInterface.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_PRINTER);
            when(usbInterface.getEndpointCount()).thenReturn(2);
            when(usbInterface.getEndpoint(0)).thenReturn(output);
            when(usbInterface.getEndpoint(1)).thenReturn(input);
            when(output.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);
            when(input.getType()).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK);
            when(output.getDirection()).thenReturn(UsbConstants.USB_DIR_OUT);
            when(input.getDirection()).thenReturn(UsbConstants.USB_DIR_IN);
            when(input.getMaxPacketSize()).thenReturn(64);
            HashMap<String, UsbDevice> devices = new HashMap<>();
            devices.put(device.getDeviceName(), device);
            when(manager.getDeviceList()).thenReturn(devices);
            when(manager.hasPermission(device)).thenReturn(true);
            when(manager.openDevice(device)).thenReturn(handle);
            when(handle.claimInterface(usbInterface, false)).thenReturn(true);
            when(writer.getDevice()).thenReturn(device);
            when(writer.isConnected()).thenReturn(true);
            Field field = PLUGIN.getDeclaredField("connections");
            field.setAccessible(true);
            cache = (HashMap<String, DeviceConnection>) field.get(plugin);
            cache.put("usb-1208-3623", writer);
            cache.put("usb-123", writer);
            when(handle.bulkTransfer(eq(output), any(byte[].class), anyInt(), anyInt())).thenAnswer(call -> {
                check(!cache.containsKey("usb-1208-3623") && !cache.containsKey("usb-123"),
                    "cached writers removed before any status request");
                commands.add((int) ((byte[]) call.getArgument(1))[2]);
                return 3;
            });
            when(handle.bulkTransfer(eq(input), any(byte[].class), anyInt(), anyInt())).thenAnswer(call -> {
                if (commands.isEmpty()) return -1;
                int reply = commands.get(commands.size() - 1) == 4 ? paper : offline;
                if (reply < 0) return -1;
                ((byte[]) call.getArgument(1))[0] = (byte) reply;
                return 1;
            });
        }

        JSONObject status() {
            CallbackContext callback = mock(CallbackContext.class);
            plugin.execute("getPrinterStatus", new JSONArray().put(new JSONObject()
                .put("type", "usb").put("id", 123).put("vendorId", 1208).put("productId", 3623)), callback);
            ArgumentCaptor<JSONObject> value = ArgumentCaptor.forClass(JSONObject.class);
            verify(callback).success(value.capture());
            verifyNoMoreInteractions(callback);
            return value.getValue();
        }
    }

    private static JSONObject unknown(ThermalPrinterCordovaPlugin plugin) throws Exception {
        return (JSONObject) invoke(plugin, "unknownPrinterStatus", new Class<?>[] { String.class, Object.class },
            "timeout", JSONObject.NULL);
    }

    private static Integer query(ThermalPrinterCordovaPlugin plugin, JSONObject result, byte[] response,
                                 int readCount, int writeCount, long deadline) throws Exception {
        startClock();
        UsbDeviceConnection connection = mock(UsbDeviceConnection.class);
        UsbEndpoint input = mock(UsbEndpoint.class);
        UsbEndpoint output = mock(UsbEndpoint.class);
        AtomicInteger reads = new AtomicInteger();
        List<byte[]> requests = new ArrayList<>();
        when(connection.bulkTransfer(eq(output), any(byte[].class), anyInt(), anyInt())).thenAnswer(call -> {
            check((int) call.getArgument(3) > 0 && (int) call.getArgument(3) <= TRANSFER_TIMEOUT_MS, "bounded write timeout");
            requests.add(((byte[]) call.getArgument(1)).clone());
            return writeCount;
        });
        when(connection.bulkTransfer(eq(input), any(byte[].class), anyInt(), anyInt())).thenAnswer(call -> {
            reads.incrementAndGet();
            check((int) call.getArgument(3) > 0 && (int) call.getArgument(3) <= TRANSFER_TIMEOUT_MS, "bounded read timeout");
            System.arraycopy(response, 0, call.getArgument(1), 0, response.length);
            return readCount;
        });
        Integer reply = (Integer) invoke(plugin, "queryUsbStatusByte", new Class<?>[] {
            UsbDeviceConnection.class, UsbEndpoint.class, UsbEndpoint.class, byte[].class,
            int.class, String.class, JSONObject.class, long.class
        }, connection, input, output, new byte[64], 4, "paper", result, deadline);
        if (deadline <= 100) {
            verifyNoInteractions(connection);
        } else {
            check(requests.size() == 1 && Arrays.equals(requests.get(0), new byte[] { 0x10, 0x04, 4 }),
                "exact paper status command");
            // The read path polls until the reply arrives or its budget expires, so more than one read
            // is legitimate. What must never happen is any read at all after a partial write.
            check(writeCount == 3 ? reads.get() >= 1 : reads.get() == 0, "do not read after partial write");
        }
        return reply;
    }
}
