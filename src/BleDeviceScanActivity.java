package org.aprsdroid.app;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Device picker for the standard BLE-KISS service.
 *
 * API 21 bluetooth.le references are kept in Scanner21 so APRSdroid remains
 * loadable on its pre-Lollipop minimum SDK.
 */
public class BleDeviceScanActivity extends Activity {
    private static final int REQUEST_BLE_PERMISSIONS = 4201;

    private final ArrayList<String> labels = new ArrayList<>();
    private final ArrayList<String> addresses = new ArrayList<>();
    private final ArrayList<BluetoothDevice> devices = new ArrayList<>();

    private ArrayAdapter<String> listAdapter;
    private TextView statusView;
    private Scanner21 scanner21;
    private GattVerifier verifier;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.p_ble_scan);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        statusView = new TextView(this);
        statusView.setText(R.string.ble_scanning);
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(listAdapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= devices.size())
                return;

            BluetoothDevice device = devices.get(position);
            String address = addresses.get(position);
            String label = labels.get(position);

            // Never keep a stale/incorrect selection while validating a new one.
            PreferenceManager.getDefaultSharedPreferences(this)
                    .edit()
                    .remove("ble.mac")
                    .remove("ble.name")
                    .apply();

            stopScan();
            statusView.setText(getString(R.string.ble_validating, label));
            verifier = new GattVerifier(this, device, address, label);
            verifier.start();
        });

        setContentView(root);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            statusView.setText(R.string.ble_error_unsupported);
            return;
        }

        ensurePermissionsAndScan();
    }

    private void ensurePermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                }, REQUEST_BLE_PERMISSIONS);
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, REQUEST_BLE_PERMISSIONS);
                return;
            }
        }

        if (!isLocationEnabledForLegacyBle()) {
            statusView.setText(R.string.ble_location_required);
            return;
        }

        startScan21();
    }

    private boolean isLocationEnabledForLegacyBle() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            return true;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                LocationManager lm =
                        (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                return lm != null && lm.isLocationEnabled();
            }

            return Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLE_PERMISSIONS)
            return;

        if (grantResults.length == 0) {
            statusView.setText(R.string.ble_scan_permission);
            return;
        }

        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                statusView.setText(R.string.ble_scan_permission);
                return;
            }
        }

        if (!isLocationEnabledForLegacyBle()) {
            statusView.setText(R.string.ble_location_required);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            startScan21();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startScan21() {
        stopScan();
        labels.clear();
        addresses.clear();
        devices.clear();
        listAdapter.notifyDataSetChanged();

        scanner21 = new Scanner21(this, labels, addresses, devices, listAdapter, statusView);
        scanner21.start();
    }

    private void stopScan() {
        if (scanner21 != null) {
            scanner21.stop();
            scanner21 = null;
        }
    }

    private void stopVerifier() {
        if (verifier != null) {
            verifier.stop();
            verifier = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopScan();
        stopVerifier();
        super.onDestroy();
    }

    @TargetApi(Build.VERSION_CODES.O)
    private static final class GattVerifier {
        private static final java.util.UUID KISS_SERVICE_UUID =
                java.util.UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb");
        private static final java.util.UUID KISS_TX_UUID =
                java.util.UUID.fromString("00000002-ba2a-46c9-ae49-01b0961f68bb");
        private static final java.util.UUID KISS_RX_UUID =
                java.util.UUID.fromString("00000003-ba2a-46c9-ae49-01b0961f68bb");
        private static final int MAX_ATTEMPTS = 3;
        private static final int GATT_ERROR_133 = 133;

        private final BleDeviceScanActivity activity;
        private final BluetoothDevice device;
        private final String address;
        private final String label;
        private final android.os.Handler handler =
                new android.os.Handler(android.os.Looper.getMainLooper());

        private android.bluetooth.BluetoothGatt gatt;
        private int attempt;
        private boolean stopped;

        GattVerifier(BleDeviceScanActivity activity,
                     BluetoothDevice device,
                     String address,
                     String label) {
            this.activity = activity;
            this.device = device;
            this.address = address;
            this.label = label;
        }

        void start() {
            attempt = 0;
            connect();
        }

        private void connect() {
            if (stopped)
                return;

            closeGatt();
            attempt++;
            activity.statusView.setText(
                    activity.getString(R.string.ble_validating_attempt, label, attempt, MAX_ATTEMPTS));

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    gatt = device.connectGatt(
                            activity.getApplicationContext(),
                            false,
                            callback,
                            BluetoothDevice.TRANSPORT_LE,
                            BluetoothDevice.PHY_LE_1M_MASK,
                            handler);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    gatt = device.connectGatt(
                            activity.getApplicationContext(),
                            false,
                            callback,
                            BluetoothDevice.TRANSPORT_LE);
                } else {
                    gatt = device.connectGatt(
                            activity.getApplicationContext(),
                            false,
                            callback);
                }

                if (gatt == null)
                    fail(activity.getString(R.string.ble_validation_connect_failed));
            } catch (SecurityException e) {
                fail(activity.getString(R.string.ble_scan_permission));
            } catch (Exception e) {
                fail(activity.getString(R.string.ble_validation_connect_failed));
            }
        }

        private final android.bluetooth.BluetoothGattCallback callback =
                new android.bluetooth.BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(
                    android.bluetooth.BluetoothGatt cbGatt,
                    int status,
                    int newState) {
                if (stopped || cbGatt != gatt)
                    return;

                if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                    if (status == GATT_ERROR_133 && attempt < MAX_ATTEMPTS) {
                        closeGatt();
                        handler.postDelayed(() -> connect(), 750L * attempt);
                    } else {
                        fail(activity.getString(
                                R.string.ble_validation_gatt_failed, status));
                    }
                    return;
                }

                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    if (!cbGatt.discoverServices())
                        fail(activity.getString(R.string.ble_validation_discovery_failed));
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    fail(activity.getString(R.string.ble_validation_connect_failed));
                }
            }

            @Override
            public void onServicesDiscovered(
                    android.bluetooth.BluetoothGatt cbGatt,
                    int status) {
                if (stopped || cbGatt != gatt)
                    return;

                if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                    fail(activity.getString(
                            R.string.ble_validation_discovery_status, status));
                    return;
                }

                android.bluetooth.BluetoothGattService service =
                        cbGatt.getService(KISS_SERVICE_UUID);
                if (service == null) {
                    fail(activity.getString(R.string.ble_validation_not_kiss));
                    return;
                }

                android.bluetooth.BluetoothGattCharacteristic tx =
                        service.getCharacteristic(KISS_TX_UUID);
                android.bluetooth.BluetoothGattCharacteristic rx =
                        service.getCharacteristic(KISS_RX_UUID);

                if (tx == null || rx == null) {
                    fail(activity.getString(R.string.ble_validation_not_kiss));
                    return;
                }

                int txProps = tx.getProperties();
                int rxProps = rx.getProperties();
                boolean txWritable =
                        (txProps & android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                boolean rxNotifiable =
                        (rxProps & android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;

                if (!txWritable || !rxNotifiable) {
                    fail(activity.getString(R.string.ble_validation_not_kiss));
                    return;
                }

                PreferenceManager.getDefaultSharedPreferences(activity)
                        .edit()
                        .putString("ble.mac", address)
                        .putString("ble.name", label)
                        .apply();

                Toast.makeText(
                        activity,
                        activity.getString(R.string.ble_selected, label),
                        Toast.LENGTH_SHORT).show();

                stop();
                activity.verifier = null;
                activity.finish();
            }
        };

        private void fail(String message) {
            if (stopped)
                return;

            closeGatt();
            activity.statusView.setText(message);
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            stop();
            activity.verifier = null;

            // Re-scan so the user can select another candidate.
            handler.postDelayed(() -> {
                if (!activity.isFinishing())
                    activity.startScan21();
            }, 500);
        }

        private void closeGatt() {
            android.bluetooth.BluetoothGatt old = gatt;
            gatt = null;
            if (old != null) {
                try { old.disconnect(); } catch (Exception ignored) {}
                try { old.close(); } catch (Exception ignored) {}
            }
        }

        void stop() {
            stopped = true;
            handler.removeCallbacksAndMessages(null);
            closeGatt();
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static final class Scanner21 {
        private static final java.util.UUID KISS_SERVICE_UUID =
                java.util.UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb");
        private static final long SCAN_MS = 10000L;

        private final BleDeviceScanActivity activity;
        private final ArrayList<String> labels;
        private final ArrayList<String> addresses;
        private final ArrayList<BluetoothDevice> devices;
        private final ArrayAdapter<String> listAdapter;
        private final TextView statusView;
        private final Set<String> seen = new HashSet<>();
        private final android.os.Handler handler =
                new android.os.Handler(android.os.Looper.getMainLooper());

        private android.bluetooth.le.BluetoothLeScanner scanner;
        private boolean scanning;

        private final android.bluetooth.le.ScanCallback callback =
                new android.bluetooth.le.ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
                        addResult(result);
                    }

                    @Override
                    public void onBatchScanResults(java.util.List<android.bluetooth.le.ScanResult> results) {
                        for (android.bluetooth.le.ScanResult result : results)
                            addResult(result);
                    }

                    @Override
                    public void onScanFailed(int errorCode) {
                        scanning = false;
                        statusView.setText(activity.getString(R.string.ble_scan_failed, errorCode));
                    }
                };

        Scanner21(BleDeviceScanActivity activity,
                  ArrayList<String> labels,
                  ArrayList<String> addresses,
                  ArrayList<BluetoothDevice> devices,
                  ArrayAdapter<String> listAdapter,
                  TextView statusView) {
            this.activity = activity;
            this.labels = labels;
            this.addresses = addresses;
            this.devices = devices;
            this.listAdapter = listAdapter;
            this.statusView = statusView;
        }

        void start() {
            BluetoothManager manager =
                    (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;

            if (adapter == null) {
                statusView.setText(R.string.bt_error_unsupported);
                return;
            }
            if (!adapter.isEnabled()) {
                statusView.setText(R.string.bt_error_disabled);
                return;
            }

            try {
                scanner = adapter.getBluetoothLeScanner();
                if (scanner == null) {
                    statusView.setText(R.string.ble_scan_failed_generic);
                    return;
                }

                android.bluetooth.le.ScanSettings settings =
                        new android.bluetooth.le.ScanSettings.Builder()
                                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                                .build();

                scanning = true;
                statusView.setText(R.string.ble_scanning);
                // Do not filter at the scanner level. Some otherwise compatible
                // BLE-KISS implementations expose the standard GATT service but
                // omit the service UUID from advertisements. We validate the
                // service after connecting instead.
                scanner.startScan(null, settings, callback);

                handler.postDelayed(() -> {
                    stop();
                    statusView.setText(labels.isEmpty()
                            ? R.string.ble_scan_none
                            : R.string.ble_scan_choose);
                }, SCAN_MS);
            } catch (SecurityException e) {
                statusView.setText(R.string.ble_scan_permission);
            } catch (Exception e) {
                statusView.setText(R.string.ble_scan_failed_generic);
            }
        }

        private void addResult(android.bluetooth.le.ScanResult result) {
            if (result == null || result.getDevice() == null)
                return;

            try {
                BluetoothDevice device = result.getDevice();
                String address = device.getAddress();
                if (address == null || !seen.add(address))
                    return;

                String name = null;
                if (result.getScanRecord() != null)
                    name = result.getScanRecord().getDeviceName();
                if (name == null)
                    name = device.getName();
                if (name == null || name.trim().isEmpty())
                    name = activity.getString(R.string.ble_unnamed_device);

                boolean advertisesKiss = false;
                if (result.getScanRecord() != null &&
                        result.getScanRecord().getServiceUuids() != null) {
                    for (android.os.ParcelUuid uuid : result.getScanRecord().getServiceUuids()) {
                        if (KISS_SERVICE_UUID.equals(uuid.getUuid())) {
                            advertisesKiss = true;
                            break;
                        }
                    }
                }

                String displayName = advertisesKiss
                        ? activity.getString(R.string.ble_standard_kiss, name)
                        : name;

                labels.add(displayName + "\n" + address);
                addresses.add(address);
                devices.add(device);
                listAdapter.notifyDataSetChanged();
                statusView.setText(R.string.ble_scan_choose);
            } catch (SecurityException e) {
                statusView.setText(R.string.ble_scan_permission);
            }
        }

        void stop() {
            handler.removeCallbacksAndMessages(null);
            if (!scanning || scanner == null)
                return;

            try {
                scanner.stopScan(callback);
            } catch (SecurityException ignored) {
            }
            scanning = false;
        }
    }
}
