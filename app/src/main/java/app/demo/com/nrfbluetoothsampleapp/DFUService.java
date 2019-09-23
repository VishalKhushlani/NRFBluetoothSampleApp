package app.demo.com.nrfbluetoothsampleapp;

import android.app.Activity;

import androidx.annotation.Nullable;

import no.nordicsemi.android.dfu.DfuBaseService;

public class DFUService extends DfuBaseService {
    @Nullable
    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        return NotificationActivity.class;
    }
}
