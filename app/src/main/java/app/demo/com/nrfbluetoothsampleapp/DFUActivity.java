package app.demo.com.nrfbluetoothsampleapp;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

import no.nordicsemi.android.dfu.DfuProgressListener;
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter;
import no.nordicsemi.android.dfu.DfuServiceInitiator;
import no.nordicsemi.android.dfu.DfuServiceListenerHelper;

public class DFUActivity extends AppCompatActivity {

    TextView file_name;
    TextView file_type;
    TextView file_scope;
    TextView file_size;
    TextView file_status;
    Button action_select_file;
    Button action_upload;
    TextView textviewUploading;
    ProgressBar progressbar_file;
    TextView textviewProgress;
    private String mDfuError;
    private boolean mDfuCompleted;
    private Integer mScope;
    private boolean mResumed;
    private static final int SELECT_FILE_REQ = 1;
    private String mFilePath;
    private Uri mFileStreamUri;
    private String mInitFilePath;
    private Uri mInitFileStreamUri;
    private int mFileType;
    private static final String EXTRA_URI = "uri";
    private int mFileTypeTmp;// This value is being used when user is selecting a file not to overwrite the old value (in case he/she will cancel selecting file)
    private boolean mStatusOk;
    private static final String PREFS_DEVICE_NAME = "prefs_device_name";
    private static final String PREFS_FILE_NAME = "prefs_file_name";
    private static final String PREFS_FILE_TYPE = "prefs_file_type";
    private static final String PREFS_FILE_SCOPE = "prefs_file_scope";
    private static final String PREFS_FILE_SIZE = "prefs_file_size";
    private static final int SELECT_INIT_FILE_REQ = 2;
    Activity thisActivity;

    private final DfuProgressListener mDfuProgressListener = new DfuProgressListenerAdapter() {
        @Override
        public void onDeviceConnecting(final String deviceAddress) {
            progressbar_file.setIndeterminate(true);
            textviewProgress.setText(R.string.dfu_status_connecting);
        }

        @Override
        public void onDfuProcessStarting(final String deviceAddress) {
            progressbar_file.setIndeterminate(true);
            textviewProgress.setText(R.string.dfu_status_starting);
        }

        @Override
        public void onEnablingDfuMode(final String deviceAddress) {
            progressbar_file.setIndeterminate(true);
            textviewProgress.setText(R.string.dfu_status_switching_to_dfu);
        }

        @Override
        public void onFirmwareValidating(final String deviceAddress) {
            progressbar_file.setIndeterminate(true);
            textviewProgress.setText(R.string.dfu_status_validating);
        }

        @Override
        public void onDeviceDisconnecting(final String deviceAddress) {
            progressbar_file.setIndeterminate(true);
            textviewProgress.setText(R.string.dfu_status_disconnecting);
        }

        @Override
        public void onDfuCompleted(final String deviceAddress) {
            textviewProgress.setText(R.string.dfu_status_completed);
            if (mResumed) {
                // let's wait a bit until we cancel the notification. When canceled immediately it will be recreated by service again.
                new Handler().postDelayed(() -> {
                    onTransferCompleted();

                    // if this activity is still open and upload process was completed, cancel the notification
                    final NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    manager.cancel(DFUService.NOTIFICATION_ID);
                }, 200);
            } else {
                // Save that the DFU process has finished
                mDfuCompleted = true;
            }
        }

        @Override
        public void onDfuAborted(final String deviceAddress) {
            textviewProgress.setText(R.string.dfu_status_aborted);
            // let's wait a bit until we cancel the notification. When canceled immediately it will be recreated by service again.
            new Handler().postDelayed(() -> {
                onUploadCanceled();

                // if this activity is still open and upload process was completed, cancel the notification
                final NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                manager.cancel(DFUService.NOTIFICATION_ID);
            }, 200);
        }

        @Override
        public void onProgressChanged(final String deviceAddress, final int percent, final float speed, final float avgSpeed, final int currentPart, final int partsTotal) {
            progressbar_file.setIndeterminate(false);
            progressbar_file.setProgress(percent);
            textviewProgress.setText(Integer.toString(percent));
            if (partsTotal > 1)
                textviewUploading.setText(getString(R.string.dfu_status_uploading_part, currentPart, partsTotal));
            else
                textviewUploading.setText(R.string.dfu_status_uploading);
        }

        @Override
        public void onError(final String deviceAddress, final int error, final int errorType, final String message) {
            if (mResumed) {
                showErrorMessage(message);

                // We have to wait a bit before canceling notification. This is called before DfuService creates the last notification.
                new Handler().postDelayed(() -> {
                    // if this activity is still open and upload process was completed, cancel the notification
                    final NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    manager.cancel(DFUService.NOTIFICATION_ID);
                }, 200);
            } else {
                mDfuError = message;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dfu);
        thisActivity = this;
        file_name = findViewById(R.id.file_name);
        file_type = findViewById(R.id.file_type);
        file_scope = findViewById(R.id.file_scope);
        file_size = findViewById(R.id.file_size);
        file_status = findViewById(R.id.file_status);
        action_select_file = findViewById(R.id.action_select_file);
        action_upload = findViewById(R.id.action_upload);
        textviewUploading = findViewById(R.id.textviewUploading);
        progressbar_file = findViewById(R.id.progressbar_file);
        textviewProgress = findViewById(R.id.textviewProgress);
        DfuServiceListenerHelper.registerProgressListener(this, mDfuProgressListener);
        action_upload.setOnClickListener(view -> {
            onUploadClicked();
        });
        action_select_file.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fileDownload();
            }
        });
    }

    private void showErrorMessage(final String message) {
        clearUI(false);
        showToast("Upload failed: " + message);
    }

    private void clearUI(final boolean clearDevice) {
        progressbar_file.setVisibility(View.INVISIBLE);
        textviewProgress.setVisibility(View.INVISIBLE);
        action_upload.setVisibility(View.INVISIBLE);
        action_select_file.setEnabled(true);
        action_upload.setEnabled(false);
        action_upload.setText("Upload");
        if (clearDevice) {
//            mSelectedDevice = null;
//            mDeviceNameView.setText(R.string.dfu_default_name);
        }
        // Application may have lost the right to these files if Activity was closed during upload (grant uri permission). Clear file related values.
        file_name.setText(null);
        file_type.setText(null);
        file_scope.setText(null);
        file_size.setText(null);
        file_status.setText("No File");
        mFilePath = null;
        mFileStreamUri = null;
        mInitFilePath = null;
        mInitFileStreamUri = null;
        mStatusOk = false;
    }

    private void showToast(final String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mResumed = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
    }

    public void onUploadCanceled() {
        clearUI(false);
        showToast("Uploading of the application has been canceled.");
    }

    private void onTransferCompleted() {
        clearUI(true);
        showToast("Application has been transferred successfully.");
    }

    public void onUploadClicked() {
        if (isDfuServiceRunning()) {
            showToast("");
            return;
        }

//         Check whether the selected file is a HEX file (we are just checking the extension)
        if (!mStatusOk) {
            Toast.makeText(this, R.string.dfu_file_status_invalid_message, Toast.LENGTH_LONG).show();
            return;
        }

        // Save current state in order to restore it if user quit the Activity
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        final SharedPreferences.Editor editor = preferences.edit();
        editor.putString(PREFS_DEVICE_NAME, Common.selectedDevice.getName());
        editor.putString(PREFS_FILE_NAME, file_name.getText().toString());
        editor.putString(PREFS_FILE_TYPE, file_type.getText().toString());
        editor.putString(PREFS_FILE_SCOPE, file_scope.getText().toString());
        editor.putString(PREFS_FILE_SIZE, file_size.getText().toString());
        editor.apply();

        showProgressBar();

        final boolean keepBond =  false;
        final boolean forceDfu = false;
        final boolean enablePRNs = Build.VERSION.SDK_INT < Build.VERSION_CODES.M;
        String value = String.valueOf(DfuServiceInitiator.DEFAULT_PRN_VALUE);
        int numberOfPackets;
        try {
            numberOfPackets = Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            numberOfPackets = DfuServiceInitiator.DEFAULT_PRN_VALUE;
        }

        final DfuServiceInitiator starter = new DfuServiceInitiator(Common.selectedDevice.getAddress())
                .setDeviceName(Common.selectedDevice.getName())
                .setForeground(false)
                .setDisableNotification(true)
                .setKeepBond(keepBond)
                .setForceDfu(forceDfu)
                .setPacketsReceiptNotificationsEnabled(enablePRNs)
                .setPacketsReceiptNotificationsValue(numberOfPackets)
                .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true);
        if (mFileType == DFUService.TYPE_AUTO) {
            starter.setZip(mFileStreamUri, mFilePath);
            if (mScope != null)
                starter.setScope(mScope);
        } else {
//            starter.setBinOrHex(mFileType, mFileStreamUri, mFilePath).setInitFile(mInitFileStreamUri, mInitFilePath);
        }
        starter.start(this, DFUService.class);
    }

    private void showProgressBar() {
        progressbar_file.setVisibility(View.VISIBLE);
        textviewProgress.setVisibility(View.VISIBLE);
        textviewProgress.setText(null);
        textviewUploading.setText(R.string.dfu_status_uploading);
        textviewUploading.setVisibility(View.VISIBLE);
        action_upload.setEnabled(false);
        action_select_file.setEnabled(false);
        action_upload.setEnabled(true);
        action_upload.setText("CANCEL");
    }

    private boolean isDfuServiceRunning() {
        final ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (DFUService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void fileDownload() {
        new DownloadFile().execute("https://drive.google.com/open?id=1braEhVPpJwMd-VWty3PUSYmpCgfIlR-N", "app_dfu_package5.zip");
    }

    private void updateFileInfo(final String fileName, final long fileSize, final int fileType) {
        file_name.setText(fileName);
        switch (fileType) {
            case DFUService.TYPE_AUTO:
                file_type.setText(getResources().getStringArray(R.array.dfu_file_type)[0]);
                break;
            case DFUService.TYPE_SOFT_DEVICE:
                file_type.setText(getResources().getStringArray(R.array.dfu_file_type)[1]);
                break;
            case DFUService.TYPE_BOOTLOADER:
                file_type.setText(getResources().getStringArray(R.array.dfu_file_type)[2]);
                break;
            case DFUService.TYPE_APPLICATION:
                file_type.setText(getResources().getStringArray(R.array.dfu_file_type)[3]);
                break;
        }
        file_size.setText(getString(R.string.dfu_file_size_text, fileSize));
        file_scope.setText("Not Available");
        final String extension = mFileType == DFUService.TYPE_AUTO ? "(?i)ZIP" : "(?i)HEX|BIN"; // (?i) =  case insensitive
        final boolean statusOk = mStatusOk = MimeTypeMap.getFileExtensionFromUrl(fileName).matches(extension);
        file_status.setText(statusOk ? R.string.dfu_file_status_ok : R.string.dfu_file_status_invalid);
        action_upload.setEnabled(Common.selectedDevice != null && statusOk);

        // Ask the user for the Init packet file if HEX or BIN files are selected. In case of a ZIP file the Init packets should be included in the ZIP.
        if (statusOk) {
            if (fileType != DFUService.TYPE_AUTO) {
                mScope = null;
                file_scope.setText("Not Available");
                new AlertDialog.Builder(this).setTitle(R.string.dfu_file_init_title).setMessage(R.string.dfu_file_init_message)
                        .setNegativeButton("NO", (dialog, which) -> {
                            mInitFilePath = null;
                            mInitFileStreamUri = null;
                        }).setPositiveButton("YES", (dialog, which) -> {
                    final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType(DFUService.MIME_TYPE_OCTET_STREAM);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, SELECT_INIT_FILE_REQ);
                    action_upload.setEnabled(true);
                }).show();
            } else {
                new AlertDialog.Builder(this).setTitle(R.string.dfu_file_scope_title).setCancelable(false)
                        .setSingleChoiceItems(R.array.dfu_file_scope, 0, (dialog, which) -> {
                            switch (which) {
                                case 0:
                                    mScope = null;
                                    break;
                                case 1:
                                    mScope = DfuServiceInitiator.SCOPE_SYSTEM_COMPONENTS;
                                    break;
                                case 2:
                                    mScope = DfuServiceInitiator.SCOPE_APPLICATION;
                                    break;
                            }
                        }).setPositiveButton("Ok", (dialogInterface, i) -> {
                    int index;
                    if (mScope == null) {
                        index = 0;
                    } else if (mScope == DfuServiceInitiator.SCOPE_SYSTEM_COMPONENTS) {
                        index = 1;
                    } else {
                        index = 2;
                    }
                    file_scope.setText(getResources().getStringArray(R.array.dfu_file_scope)[index]);
                }).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DfuServiceListenerHelper.unregisterProgressListener(this, mDfuProgressListener);
    }

    private class DownloadFile extends AsyncTask<String, Void, Void> {

        @Override
        protected Void doInBackground(String... strings) {
            String fileUrl = strings[0];
            String fileName = strings[1];
            boolean isFolderCreated = true;
            String extStorageDirectory = Environment.getExternalStorageDirectory().toString();
            File folder = new File(extStorageDirectory, "hardwarecode");
            if(!folder.exists()){
                isFolderCreated = folder.mkdirs();
            }

            if(isFolderCreated){
                File codeFile = new File(folder, fileName);
                if(codeFile.exists()){
                    codeFile.delete();
                }
                try {
                    codeFile.createNewFile();
                    if (FileDownloader.downloadFile(fileUrl, codeFile)) {
                        readFile();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }


            }

            return null;
        }
    }

    private void readFile(){
        File codeFile = new File(Environment.getExternalStorageDirectory() + "/hardwarecode/" + "app_dfu_package5.zip");
        thisActivity.runOnUiThread(()->{
            updateFileInfo(codeFile.getName(),codeFile.length(),DFUService.TYPE_AUTO);
        });
    }


}
