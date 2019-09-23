package app.demo.com.nrfbluetoothsampleapp;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NRF";
    private static final String cusTAG = "NRFBLE";
    Button connect_btn;
    Button scan_btn;
    Button send_btn;
    EditText send_txt;
    private static final int REQUEST_ENABLE_BT = 2;
    private static final int MAX_MTU = 512;
    BluetoothDevice device;
    BluetoothGatt bluetoothGatt;
    BluetoothGattCallback bluetoothGattCallback;
    Context thisActivity;
    private BroadcastReceiver bleDiscoveryBroadcastReceiver;
    private IntentFilter bleDiscoveryIntentFilter;
    ArrayAdapter<BluetoothDevice> arrayAdapter;
    BluetoothAdapter bluetoothAdapter;
    private ArrayList<byte[]> writeBuffer = new ArrayList<>();
    private static final int PAYLOAD_SIZE = 20;
    private boolean writePending;
    private BluetoothGattCharacteristic readCharacteristic, writeCharacteristic;
    private static final UUID BLUETOOTH_LE_CCCD           = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID BLUETOOTH_LE_NRF_SERVICE    = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID BLUETOOTH_LE_NRF_CHAR_RW2   = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID BLUETOOTH_LE_NRF_CHAR_RW3   = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private String readStringMaster;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        thisActivity = this;
        connect_btn = findViewById(R.id.connect_btn);
        scan_btn = findViewById(R.id.scan_btn);
        send_btn = findViewById(R.id.send_btn);
        send_txt = findViewById(R.id.send_txt);

        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 0);


        if (isBLESupported()){
            Toast.makeText(this,"BLE Supported",Toast.LENGTH_LONG).show();
        }else {
            Toast.makeText(this,"BLE Not Supported",Toast.LENGTH_LONG).show();
        }

        if(!isBLEEnabled()){
            showBLEDialog();
        }

        showBluetoothDeviceListPopup();

        connect_btn.setOnClickListener(view -> {
            startActivity(new Intent(MainActivity.this,DFUActivity.class));
        });

        scan_btn.setOnClickListener(view -> {
            bluetoothAdapter.startDiscovery();
            showBluetoothDeviceListPopup();
        });

        send_btn.setOnClickListener(view -> {
            try {
                write(send_txt.getText().toString().getBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        bluetoothGattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if(newState  == BluetoothProfile.STATE_CONNECTED){
                    if (!gatt.discoverServices()) {
                        showToast("discoverServices failed");
                    }
                    showToast("BLE STATE_CONNECTED");
                }else if(newState  == BluetoothProfile.STATE_CONNECTING){
                    showToast("BLE STATE_CONNECTING");
                }else if(newState  == BluetoothProfile.STATE_DISCONNECTED){
                    showToast("BLE STATE_DISCONNECTED");
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                if(characteristic==readCharacteristic){
                    byte[] data = readCharacteristic.getValue();
                    Log.v(cusTAG, "before process: " + new String(data));
                    processReceivedData(new String(data));
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                connectCharacteristics1(gatt);
            }

            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                Log.d(TAG,"mtu size "+mtu+", status="+status);
                super.onMtuChanged(gatt, mtu, status);
                connectCharacteristics2(gatt);
            }

            @Override
            public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                if(descriptor.getCharacteristic()==readCharacteristic){
                    if(readCharacteristic.getValue()!=null){
                        byte[] data = readCharacteristic.getValue();
                        showToast(new String(data));
                    }
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if(status != BluetoothGatt.GATT_SUCCESS) {
                    Log.v(cusTAG,"Write failed.");
                    return;
                }
                if(characteristic == writeCharacteristic) {
                    Log.d(TAG,"write finished, status="+status);
                    writeNext();
                }
            }
        };

        bleDiscoveryBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(intent.getAction().equals(BluetoothDevice.ACTION_FOUND)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if(device.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                        runOnUiThread(() -> updateScan(device));
                    }
                }
                if(intent.getAction().equals((BluetoothAdapter.ACTION_DISCOVERY_FINISHED))) {
                    if (bluetoothAdapter!=null) {
//                        bluetoothAdapter.startDiscovery();
                    }
                }
            }
        };
        bleDiscoveryIntentFilter = new IntentFilter();
        bleDiscoveryIntentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        bleDiscoveryIntentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

    }

    @Override
    protected void onResume() {
        registerReceiver(bleDiscoveryBroadcastReceiver, bleDiscoveryIntentFilter);
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(bleDiscoveryBroadcastReceiver);
        super.onDestroy();
    }

    private void updateScan(BluetoothDevice device) {
            arrayAdapter.add(device);
            arrayAdapter.notifyDataSetChanged();
    }

    private void showBLEDialog() {
        final Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
    }

    private boolean isBLESupported() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    private boolean isBLEEnabled() {
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        bluetoothAdapter.startDiscovery();
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    private void showBluetoothDeviceListPopup() {
        AlertDialog.Builder builderSingle = new AlertDialog.Builder(this);
        builderSingle.setTitle("Connect the device");
        builderSingle.setCancelable(false);

        // current implementation only shows already paired, bonded devices
        arrayAdapter = new ArrayAdapter<>(this, android.R.layout.select_dialog_item);
//        for (BluetoothDevice bluetoothDevice : bluetoothAdapter.getBondedDevices()) {
//            arrayAdapter.add(bluetoothDevice);
//        }

        builderSingle.setNegativeButton("cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        builderSingle.setPositiveButton("Scan", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                bluetoothAdapter.startDiscovery();
            }
        });

        builderSingle.setAdapter(arrayAdapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int pos) {
                device = arrayAdapter.getItem(pos);
                if (device != null) {
                    Common.selectedDevice = device;
                    bluetoothGatt = device.connectGatt(thisActivity,true,bluetoothGattCallback,BluetoothDevice.TRANSPORT_LE);
                }
            }
        });
        builderSingle.show();
    }

    private void connectCharacteristics1(BluetoothGatt gatt) {
        for (BluetoothGattService gattService : gatt.getServices()) {
            if (gattService.getUuid().equals(BLUETOOTH_LE_NRF_SERVICE)) {
                Log.d(TAG, "service nrf uart");
                //for(BluetoothGattCharacteristic characteristic : gattService.getCharacteristics())
                //    Log.d(TAG, "characteristic "+characteristic.getUuid());
                BluetoothGattCharacteristic rw2 = gattService.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW2);
                BluetoothGattCharacteristic rw3 = gattService.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW3);
                if (rw2 != null && rw3 != null) {
                    int rw2prop = rw2.getProperties();
                    int rw3prop = rw3.getProperties();
                    boolean rw2write = (rw2prop & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                    boolean rw3write = (rw3prop & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                    Log.d(TAG, "characteristic properties " + rw2prop + "/" + rw3prop);
                    if (rw2write && rw3write) {
//                        onSerialConnectError(new IOException("multiple write characteristics (" + rw2prop + "/" + rw3prop + ")"));
                        return;
                    } else if (rw2write) { // some devices use this ...
                        writeCharacteristic = rw2;
                        readCharacteristic = rw3;
                    } else if (rw3write) { // ... and other devices use this characteristic
                        writeCharacteristic = rw3;
                        readCharacteristic = rw2;
                    } else {
//                        onSerialConnectError(new IOException("no write characteristic (" + rw2prop + "/" + rw3prop));
                        return;
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "request max MTU");
            if (!gatt.requestMtu(MAX_MTU))
                showToast("request MTU failed");
            // continues asynchronously in onMtuChanged
        } else {
            connectCharacteristics2(gatt);
        }
    }

    private void connectCharacteristics2(BluetoothGatt gatt) {
        if(readCharacteristic==null || writeCharacteristic==null) {
            for (BluetoothGattService gattService : gatt.getServices()) {
                Log.d(TAG, "service "+gattService.getUuid());
            }
//            onSerialConnectError(new IOException("no serial profile found"));
            return;
        }
        int writeProperties = writeCharacteristic.getProperties();
        if((writeProperties & (BluetoothGattCharacteristic.PROPERTY_WRITE +     // Microbit,HM10-clone have WRITE
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) ==0) { // HM10,TI uart have only WRITE_NO_RESPONSE
//            onSerialConnectError(new IOException("write characteristic not writable"));
            return;
        }
        if(!gatt.setCharacteristicNotification(readCharacteristic,true)) {
//            onSerialConnectError(new IOException("no notification for read characteristic"));
            return;
        }
        BluetoothGattDescriptor readDescriptor = readCharacteristic.getDescriptor(BLUETOOTH_LE_CCCD);
        if(readDescriptor == null) {
//            onSerialConnectError(new IOException("no CCCD descriptor for read characteristic"));
            return;
        }
        int readProperties = readCharacteristic.getProperties();
        if((readProperties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            Log.d(TAG, "enable read indication");
            readDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        }else if((readProperties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            Log.d(TAG, "enable read notification");
            readDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
//            onSerialConnectError(new IOException("no indication/notification for read characteristic ("+readProperties+")"));
            return;
        }
        Log.d(TAG,"writing read characterictic descriptor");
        if(!gatt.writeDescriptor(readDescriptor)) {
//            onSerialConnectError(new IOException("read characteristic CCCD descriptor not writable"));
        }
        // continues asynchronously in onDescriptorWrite()
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(thisActivity,message,Toast.LENGTH_LONG).show());
    }

    private void processReceivedData(String s) {
        if (readStringMaster == null) {
            readStringMaster = "";
        }

        Log.v(cusTAG, "in process: " + s);

        readStringMaster += s;
        int dataStartIndex = readStringMaster.indexOf("{");
        int dataEndIndex = readStringMaster.indexOf("}");

        String data = null;
        if(dataStartIndex != -1 && dataEndIndex != -1){
            data = readStringMaster.substring(dataStartIndex, dataEndIndex+1);
            if (readStringMaster.length() >= dataEndIndex+2) {
                readStringMaster = readStringMaster.substring(dataEndIndex+2);
            } else {
                readStringMaster = "";
            }
        }
        if (data != null) {
            Log.i(TAG, "processed: " + data);
            showToast(data);
        }
    }

    void write(byte[] data) throws IOException {
        if(writeCharacteristic == null)
            throw new IOException("not connected");
        byte[] data0;
        synchronized (writeBuffer) {
            if(data.length <= PAYLOAD_SIZE) {
                data0 = data;
            } else {
                data0 = Arrays.copyOfRange(data, 0, PAYLOAD_SIZE);
            }
            if(!writePending && writeBuffer.isEmpty()) {
                writePending = true;
            } else {
                writeBuffer.add(data0);
                Log.d(TAG,"write queued, len="+data0.length);
                data0 = null;
            }
            if(data.length > PAYLOAD_SIZE) {
                for(int i = 1; i<(data.length+ PAYLOAD_SIZE -1)/ PAYLOAD_SIZE; i++) {
                    int from = i* PAYLOAD_SIZE;
                    int to = Math.min(from+ PAYLOAD_SIZE, data.length);
                    writeBuffer.add(Arrays.copyOfRange(data, from, to));
                    Log.d(TAG,"write queued, len="+(to-from));
                }
            }
        }
        if(data0 != null) {
            writeCharacteristic.setValue(data0);
            if (!bluetoothGatt.writeCharacteristic(writeCharacteristic)) {
                Log.v(cusTAG,"write failed");
            } else {
                Log.d(TAG,"write started, len="+data0.length);
            }
        }
        // continues asynchronously in onCharacteristicWrite()
    }

    private void writeNext() {
        final byte[] data;
        synchronized (writeBuffer) {
            if (!writeBuffer.isEmpty()) {
                writePending = true;
                data = writeBuffer.remove(0);
            } else {
                writePending = false;
                data = null;
            }
        }
        if(data != null) {
            writeCharacteristic.setValue(data);
            if (!bluetoothGatt.writeCharacteristic(writeCharacteristic)) {
                Log.v(cusTAG,"write failed");
            } else {
                Log.d(TAG,"write started, len="+data.length);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String permissions[], @NonNull int[] grantResults) {
        // ignore requestCode as there is only one in this fragment
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter.startDiscovery();
        } else {
//            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
//            builder.setTitle(getText(R.string.location_denied_title));
//            builder.setMessage(getText(R.string.location_denied_message));
//            builder.setPositiveButton(android.R.string.ok, null);
//            builder.show();
        }
    }

}
