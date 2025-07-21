package com.acoby.wifiphotos;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.content.Intent;
import android.os.Environment;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    public static String TAG = "WifiPhotos";

    private HttpServer httpServer;
    private Cache cache;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock1;
    private WifiManager.WifiLock wifiLock2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If permissions are pending, request them and wait for callback
        if (!ensurePermissionsGranted()) {
            return;
        }

        initAfterPermissions();
    }

    /**
     * Continue initialization once storage permissions are granted.
     */
    private void initAfterPermissions() {
        // Display IP address in phone UI.
        WifiManager wifiMgr = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
        int ip = wifiInfo.getIpAddress();
        String ipAddress = Formatter.formatIpAddress(ip);
        TextView instruction1Txt = findViewById(R.id.instruction1);
        TextView instruction2Txt = findViewById(R.id.instruction2);
        TextView ipAddrTxt = findViewById(R.id.ipAddr);
        if (ip != 0 || DebugFeatures.BIND_ANY_INTERFACE) {
            instruction1Txt.setVisibility(View.VISIBLE);
            instruction2Txt.setVisibility(View.VISIBLE);
            ipAddrTxt.setText("http://" + ipAddress + ":8080");
        } else {
            instruction1Txt.setVisibility(View.GONE);
            instruction2Txt.setVisibility(View.GONE);
            ipAddrTxt.setText("Please connect device to Wi-Fi.");
        }

        // Remove the graphic in landscape mode, to ensure room for the text.
        ImageView imageView = findViewById(R.id.imageView);
        int orientation = getResources().getConfiguration().orientation;
        if (Configuration.ORIENTATION_LANDSCAPE == orientation) {
            imageView.setVisibility(View.GONE);
        } else {
            imageView.setVisibility(View.VISIBLE);
        }

        // Keep screen on while active.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Acquire wake lock for background image processing.
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wifiphotos.acoby.com::Wakelock");
        wakeLock.acquire();

        // Acquire Wi-Fi locks for best throughput.
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock1 = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wifiphotos_wifilock_highperf");
        wifiLock1.acquire();
        if (Build.VERSION.SDK_INT >= 29) {
            wifiLock2 = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "wifiphotos_wifilock_lowlatency");
            wifiLock2.acquire();
        }

        try {
            Dirs dirs = new Dirs(this);
            cache = new Cache(this, dirs);
            ImageResizer imageResizer = new ImageResizer(this, cache, dirs);
            Trash trash = new Trash(this, cache, dirs);
            if (DebugFeatures.BIND_ANY_INTERFACE) {
                ipAddress = null; // bind to any interface
            }
            if (ip != 0 || DebugFeatures.BIND_ANY_INTERFACE) {
                Log.v(TAG, "Starting HTTP server");
                httpServer = new HttpServer(this, imageResizer, trash, cache, dirs, ipAddress);
                httpServer.start();
            }
        } catch (Exception e) {
            Log.v(TAG, Log.getStackTraceString(e));
        }

    }

    @Override
    protected void onPause() {
        super.onPause();

        if (this.wakeLock != null) {
            this.wakeLock.release();
            this.wakeLock = null;
        }

        if (this.wifiLock1 != null) {
            this.wifiLock1.release();
            this.wifiLock1 = null;
        }

        if (this.wifiLock2 != null) {
            this.wifiLock2.release();
            this.wifiLock2 = null;
        }

        if (this.httpServer != null) {
            this.httpServer.stop();
            this.httpServer  = null;
        }

        if (this.cache != null) {
            this.cache.close();
            this.cache = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    public void viewAbout(View v) {
        View aboutView = LayoutInflater.from(this).inflate(R.layout.dialog_about, null);
        AlertDialog a = new AlertDialog.Builder(this, R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("About")
                .setView(aboutView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public void viewLicenses(View v) {
        // Inspired by https://www.bignerdranch.com/blog/open-source-licenses-and-android/
        WebView view = (WebView) LayoutInflater.from(this).inflate(R.layout.dialog_licenses, null);
        view.loadUrl("file:///android_asset/open_source_licenses.html");
        AlertDialog mAlertDialog = new AlertDialog.Builder(this, R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("Licenses")
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Log.v(TAG,"Permissions not granted");
                if (this.httpServer != null) {
                    this.httpServer.stop();
                }
                this.finish();
                return;
            }
        }
        // Permissions granted: continue setup
        initAfterPermissions();
    }

    private boolean ensurePermissionsGranted() {
        // On Android 11+ require Manage All Files permission for deleting media
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return false;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> perms = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ requires media permissions instead of broad external storage
                if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                        != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.READ_MEDIA_IMAGES);
                }
            } else {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }
            if (!perms.isEmpty()) {
                String[] req = perms.toArray(new String[0]);
                Log.v(TAG, "Requesting permissions " + Arrays.toString(req));
                ActivityCompat.requestPermissions(this, req, 100);
                return false;
            }
        } else {
            Log.v(TAG, "Storage permissions automatically granted on SDK <23");
        }
        return true;
    }
}
