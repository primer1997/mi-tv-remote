package com.prateek.mitvremote.bt;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bluetooth fallback transport: the phone acts as a Bluetooth HID keyboard
 * (keyboard + consumer-control reports) paired with the TV.
 *
 * The phone must be Bluetooth-paired with the TV first (Android Settings);
 * then the TV is selected here and the HID channel is connected.
 */
public class BtRemote {
    public interface Callback {
        void onStatus(String status);
        void onRegistered();
        void onConnected(String deviceName);
        void onDisconnected();
        void onError(String message);
        void onUnsupported(String reason);
    }

    // HID report descriptor: report ID 1 = boot keyboard, report ID 2 = consumer control.
    private static final byte[] REPORT_DESC = new byte[]{
            0x05, 0x01, 0x09, 0x06, (byte) 0xA1, 0x01, (byte) 0x85, 0x01,
            0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, (byte) 0x95, 0x08, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x01,
            (byte) 0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, (byte) 0x91, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x03, (byte) 0x91, 0x01,
            (byte) 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x26, (byte) 0xFF, 0x00,
            0x05, 0x07, 0x19, 0x00, 0x2A, (byte) 0xFF, 0x00, (byte) 0x81, 0x00,
            (byte) 0xC0,
            0x05, 0x0C, 0x09, 0x01, (byte) 0xA1, 0x01, (byte) 0x85, 0x02,
            0x15, 0x00, 0x26, (byte) 0xFF, 0x03, 0x19, 0x00, 0x2A, (byte) 0xFF, 0x03,
            0x75, 0x10, (byte) 0x95, 0x01, (byte) 0x81, 0x00,
            (byte) 0xC0,
    };

    private static final int REPORT_KEYBOARD = 1;
    private static final int REPORT_CONSUMER = 2;

    /** Android keycode -> HID keyboard usage (report 1). */
    private static final Map<Integer, Integer> KB = new HashMap<>();
    /** Android keycode -> HID consumer usage (report 2). */
    private static final Map<Integer, Integer> CC = new HashMap<>();
    static {
        KB.put(19, 0x52); KB.put(20, 0x51); KB.put(21, 0x50); KB.put(22, 0x4F);
        KB.put(23, 0x28); KB.put(66, 0x28); KB.put(4, 0x29);
        CC.put(3, 0x223);            // HOME -> AC Home
        CC.put(24, 0xE9); CC.put(25, 0xEA); CC.put(164, 0xE2); // volume
        CC.put(26, 0x30);            // POWER
        CC.put(85, 0xCD); CC.put(87, 0xB5); CC.put(88, 0xB6);  // media
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Callback callback;

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice device;
    private volatile boolean appRegistered;
    private volatile boolean connected;

    public BtRemote(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
    }

    public static boolean needsRuntimePermission() {
        return Build.VERSION.SDK_INT >= 31;
    }

    public static boolean hasPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Start: check support, bind the HID_DEVICE profile, register the HID app. */
    public void start() {
        if (Build.VERSION.SDK_INT < 28) {
            post(() -> callback.onUnsupported("Bluetooth remote needs Android 9 or newer"));
            return;
        }
        BluetoothManager bm = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            post(() -> callback.onUnsupported("This phone has no Bluetooth"));
            return;
        }
        if (!adapter.isEnabled()) {
            post(() -> callback.onUnsupported("Please turn on Bluetooth first"));
            return;
        }
        post(() -> callback.onStatus("Checking Bluetooth HID support..."));
        boolean ok = adapter.getProfileProxy(appContext, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hid = (BluetoothHidDevice) proxy;
                    registerApp();
                }
            }
            @Override public void onServiceDisconnected(int profile) {
                hid = null;
            }
        }, BluetoothProfile.HID_DEVICE);
        if (!ok) post(() -> callback.onUnsupported(
                "This phone does not support Bluetooth remote control"));
    }

    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    private void registerApp() {
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "Mi TV Remote", "TV remote control", "Prateek",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD, REPORT_DESC);
        BluetoothHidDeviceAppQosSettings qos = new BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 0, 0, 0, 0, 0);
        net.execute(() -> {
            boolean accepted;
            if (Build.VERSION.SDK_INT >= 31) {
                accepted = hid.registerApp(sdp, null, qos,
                        appContext.getMainExecutor(), hidCallback);
            } else {
                // API 28-30: the 4-arg overload, via reflection (removed from newer SDKs)
                accepted = registerAppLegacy(sdp, qos);
            }
            if (!accepted) post(() -> callback.onUnsupported(
                    "Bluetooth HID registration was rejected on this phone"));
        });
    }

    private boolean registerAppLegacy(BluetoothHidDeviceAppSdpSettings sdp,
                                      BluetoothHidDeviceAppQosSettings qos) {
        try {
            java.lang.reflect.Method m = BluetoothHidDevice.class.getMethod("registerApp",
                    BluetoothHidDeviceAppSdpSettings.class,
                    BluetoothHidDeviceAppQosSettings.class,
                    BluetoothHidDeviceAppQosSettings.class,
                    BluetoothHidDevice.Callback.class);
            return (boolean) m.invoke(hid, sdp, null, qos, hidCallback);
        } catch (Exception e) {
            return false;
        }
    }

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice d, boolean registered) {
            appRegistered = registered;
            if (registered) post(() -> {
                callback.onStatus("Bluetooth ready - pick your TV");
                callback.onRegistered();
            });
        }
        @Override public void onConnectionStateChanged(BluetoothDevice d, int state) {
            if (d.equals(device)) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connected = true;
                    post(() -> callback.onConnected(nameOf(d)));
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false;
                    post(callback::onDisconnected);
                }
            }
        }
    };

    private String nameOf(BluetoothDevice d) {
        try { return d.getName() != null ? d.getName() : d.getAddress(); }
        catch (SecurityException e) { return d.getAddress(); }
    }

    /** Bonded devices the user can pick (must be paired in Android Settings first). */
    public List<BluetoothDevice> bondedDevices() {
        List<BluetoothDevice> out = new ArrayList<>();
        try {
            Set<BluetoothDevice> set = adapter.getBondedDevices();
            if (set != null) out.addAll(set);
        } catch (SecurityException ignored) {}
        return out;
    }

    public boolean isRegistered() { return appRegistered; }
    public boolean isConnected() { return connected; }

    public void connectTo(BluetoothDevice d) {
        device = d;
        net.execute(() -> {
            post(() -> callback.onStatus("Connecting Bluetooth to " + nameOf(d) + "..."));
            boolean ok = hid.connect(d);
            if (!ok) post(() -> callback.onError("Bluetooth connection was rejected"));
        });
    }

    public void disconnectDevice() {
        net.execute(() -> {
            try { if (device != null) hid.disconnect(device); }
            catch (Exception ignored) {}
            connected = false;
        });
    }

    /** Send an Android keycode as press + release. */
    public void sendKey(int androidKeyCode) {
        net.execute(() -> {
            if (!connected || device == null) return;
            try {
                Integer kb = KB.get(androidKeyCode);
                Integer cc = CC.get(androidKeyCode);
                if (kb != null) {
                    hid.sendReport(device, REPORT_KEYBOARD,
                            new byte[]{0, 0, kb.byteValue(), 0, 0, 0, 0, 0});
                    Thread.sleep(60);
                    hid.sendReport(device, REPORT_KEYBOARD, new byte[8]);
                } else if (cc != null) {
                    int u = cc;
                    hid.sendReport(device, REPORT_CONSUMER,
                            new byte[]{(byte) (u & 0xFF), (byte) ((u >> 8) & 0xFF)});
                    Thread.sleep(60);
                    hid.sendReport(device, REPORT_CONSUMER, new byte[]{0, 0});
                }
            } catch (Exception ignored) {}
        });
    }

    public void stop() {
        net.execute(() -> {
            try {
                if (hid != null && device != null) hid.disconnect(device);
            } catch (Exception ignored) {}
            try {
                if (hid != null) hid.unregisterApp();
            } catch (Exception ignored) {}
            try {
                if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            } catch (Exception ignored) {}
            hid = null;
            appRegistered = false;
            connected = false;
        });
    }

    private void post(Runnable r) { main.post(r); }
}
