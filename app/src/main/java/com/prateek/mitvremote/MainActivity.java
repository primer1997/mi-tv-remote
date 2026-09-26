package com.prateek.mitvremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.prateek.mitvremote.bt.BtRemote;
import com.prateek.mitvremote.wifi.RemoteClient;
import com.prateek.mitvremote.wifi.WifiRemote;

public class MainActivity extends Activity {
    private static final int RC_BT = 1001;
    private static final String PREFS = "mitvremote";
    private static final String KEY_TRANSPORT = "transport";

    private enum Transport { WIFI, BT }

    private Transport transport = Transport.WIFI;
    private WifiRemote wifi;
    private BtRemote bt;

    private View statusDot;
    private TextView statusText;
    private Button btnWifi, btnBt;
    private LinearLayout wifiPanel, btPanel, foundList, btDevices;
    private EditText ipInput;
    private TextView scanStatus;

    private AlertDialog pairingDialog;
    private EditText pairingInput;
    private TextView pairingError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        btnWifi = findViewById(R.id.btnWifi);
        btnBt = findViewById(R.id.btnBt);
        wifiPanel = findViewById(R.id.wifiPanel);
        btPanel = findViewById(R.id.btPanel);
        foundList = findViewById(R.id.foundList);
        btDevices = findViewById(R.id.btDevices);
        ipInput = findViewById(R.id.ipInput);
        scanStatus = findViewById(R.id.scanStatus);

        wifi = new WifiRemote(this);
        ipInput.setText(wifi.lastIp());

        btnWifi.setOnClickListener(v -> setTransport(Transport.WIFI));
        btnBt.setOnClickListener(v -> setTransport(Transport.BT));

        findViewById(R.id.btnConnect).setOnClickListener(v -> {
            String ip = ipInput.getText().toString().trim();
            if (ip.isEmpty()) {
                toast("Enter the TV's IP address");
                return;
            }
            wifi.connect(ip, wifiCallback);
        });
        findViewById(R.id.btnScan).setOnClickListener(v -> startScan());
        findViewById(R.id.btnBtEnable).setOnClickListener(v -> enableBt());

        // D-pad
        keyButton(R.id.dpadUp, RemoteClient.KEY_DPAD_UP);
        keyButton(R.id.dpadDown, RemoteClient.KEY_DPAD_DOWN);
        keyButton(R.id.dpadLeft, RemoteClient.KEY_DPAD_LEFT);
        keyButton(R.id.dpadRight, RemoteClient.KEY_DPAD_RIGHT);
        keyButton(R.id.dpadOk, RemoteClient.KEY_DPAD_CENTER);
        keyButton(R.id.btnHome, RemoteClient.KEY_HOME);
        keyButton(R.id.btnBack, RemoteClient.KEY_BACK);
        keyButton(R.id.btnVolDown, RemoteClient.KEY_VOLUME_DOWN);
        keyButton(R.id.btnVolUp, RemoteClient.KEY_VOLUME_UP);
        keyButton(R.id.btnMute, RemoteClient.KEY_VOLUME_MUTE);
        keyButton(R.id.btnPower, RemoteClient.KEY_POWER);
        keyButton(R.id.btnPlayPause, RemoteClient.KEY_MEDIA_PLAY_PAUSE);
        findViewById(R.id.btnKeyboard).setOnClickListener(v -> showKeyboardDialog());

        String saved = getPrefs().getString(KEY_TRANSPORT, "WIFI");
        setTransport("BT".equals(saved) ? Transport.BT : Transport.WIFI);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void keyButton(int id, int keyCode) {
        findViewById(id).setOnClickListener(v -> {
            if (transport == Transport.WIFI) wifi.sendKey(keyCode);
            else if (bt != null) bt.sendKey(keyCode);
            else toast("Enable Bluetooth remote first");
        });
    }

    private void setTransport(Transport t) {
        transport = t;
        getPrefs().edit().putString(KEY_TRANSPORT, t.name()).apply();
        boolean isWifi = t == Transport.WIFI;
        wifiPanel.setVisibility(isWifi ? View.VISIBLE : View.GONE);
        btPanel.setVisibility(isWifi ? View.GONE : View.VISIBLE);
        btnWifi.setEnabled(!isWifi);
        btnBt.setEnabled(isWifi);
        if (!isWifi) {
            wifi.disconnect();
            dismissPairingDialog();
            setStatus("Bluetooth mode", 0xFF888888);
        } else {
            if (bt != null) { bt.stop(); bt = null; }
            btDevices.removeAllViews();
            setStatus(wifi.isConnected() ? "Connected" : "Not connected",
                    wifi.isConnected() ? 0xFF4CAF50 : 0xFF888888);
        }
    }

    // ---------- Wi-Fi ----------

    private final WifiRemote.Callback wifiCallback = new WifiRemote.Callback() {
        @Override public void onStatus(String s) { setStatus(s, 0xFFFFC107); }
        @Override public void onConnected(String host) {
            dismissPairingDialog();
            setStatus("Connected to " + host, 0xFF4CAF50);
        }
        @Override public void onPairingCodeRequired() { showPairingDialog(); }
        @Override public void onPairingCodeError(String m) {
            if (pairingError != null) pairingError.setText(m);
        }
        @Override public void onDisconnected(String reason) { setStatus("Disconnected", 0xFF888888); }
        @Override public void onError(String m) {
            dismissPairingDialog();
            setStatus(m, 0xFFF44336);
            toast(m);
        }
    };

    private void startScan() {
        foundList.removeAllViews();
        scanStatus.setText("Scanning...");
        setStatus("Scanning network for TV...", 0xFFFFC107);
        wifi.scan(new WifiRemote.ScanListener() {
            @Override public void onFound(String ip) {
                Button b = new Button(MainActivity.this);
                b.setText("TV at " + ip);
                b.setOnClickListener(v -> {
                    ipInput.setText(ip);
                    wifi.connect(ip, wifiCallback);
                });
                foundList.addView(b);
            }
            @Override public void onDone() {
                scanStatus.setText(foundList.getChildCount() == 0 ? "No TV found" : "Done");
                if (!wifi.isConnected()) setStatus("Not connected", 0xFF888888);
            }
        });
    }

    private void showPairingDialog() {
        if (pairingDialog != null && pairingDialog.isShowing()) return;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);
        TextView hint = new TextView(this);
        hint.setText("Type the 6-character code shown on your TV screen.");
        pairingInput = new EditText(this);
        pairingInput.setHint("e.g. A1B2C3");
        pairingInput.setInputType(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        pairingError = new TextView(this);
        pairingError.setTextColor(0xFFF44336);
        layout.addView(hint);
        layout.addView(pairingInput);
        layout.addView(pairingError);
        pairingDialog = new AlertDialog.Builder(this)
                .setTitle("Pair with TV")
                .setView(layout)
                .setPositiveButton("Pair", (d, w) ->
                        wifi.submitPairingCode(pairingInput.getText().toString()))
                .setNegativeButton("Cancel", (d, w) -> wifi.cancelPairing())
                .setCancelable(false)
                .show();
    }

    private void dismissPairingDialog() {
        if (pairingDialog != null && pairingDialog.isShowing()) pairingDialog.dismiss();
        pairingDialog = null;
    }

    private void showKeyboardDialog() {
        if (transport != Transport.WIFI) {
            toast("Keyboard input works over Wi-Fi only");
            return;
        }
        if (!wifi.isConnected()) {
            toast("Connect to the TV first");
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("Type text for the TV");
        new AlertDialog.Builder(this)
                .setTitle("TV keyboard")
                .setView(input)
                .setPositiveButton("Send", (d, w) -> wifi.sendText(input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- Bluetooth ----------

    private void enableBt() {
        if (BtRemote.needsRuntimePermission()
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, RC_BT);
            return;
        }
        startBt();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_BT && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBt();
        } else if (requestCode == RC_BT) {
            toast("Bluetooth permission is needed for the Bluetooth remote");
        }
    }

    private void startBt() {
        bt = new BtRemote(this, btCallback);
        bt.start();
    }

    private final BtRemote.Callback btCallback = new BtRemote.Callback() {
        @Override public void onStatus(String s) { setStatus(s, 0xFFFFC107); }
        @Override public void onRegistered() { listBtDevices(); }
        @Override public void onConnected(String name) { setStatus("Bluetooth: " + name, 0xFF4CAF50); }
        @Override public void onDisconnected() { setStatus("Bluetooth disconnected", 0xFF888888); }
        @Override public void onError(String m) { setStatus(m, 0xFFF44336); toast(m); }
        @Override public void onUnsupported(String reason) { setStatus(reason, 0xFFF44336); toast(reason); }
    };

    private void listBtDevices() {
        btDevices.removeAllViews();
        if (bt == null) return;
        for (BluetoothDevice d : bt.bondedDevices()) {
            String name;
            try {
                name = d.getName() != null ? d.getName() : d.getAddress();
            } catch (SecurityException e) {
                name = d.getAddress();
            }
            Button b = new Button(this);
            b.setText(name);
            final BluetoothDevice dev = d;
            b.setOnClickListener(v -> bt.connectTo(dev));
            btDevices.addView(b);
        }
        if (btDevices.getChildCount() == 0) {
            TextView t = new TextView(this);
            t.setText("No paired devices. Pair the TV in Android Bluetooth settings first.");
            t.setTextColor(0xFFAAAAAA);
            btDevices.addView(t);
        }
    }

    // ---------- helpers ----------

    private void setStatus(String text, int color) {
        statusText.setText(text);
        statusDot.setBackgroundColor(color);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        wifi.disconnect();
        if (bt != null) bt.stop();
        super.onDestroy();
    }
}
