package com.acoby.wifiphotos;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    public static String LOGTAG = "wifiphotos.acoby.com";

    private HttpServer httpServer;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ensurePermissionsGranted();
    }

    @Override
    protected void onResume() {
        super.onResume();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        this.wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"wifiphotos.acoby.com::Wakelock");
        this.wakeLock.acquire();

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF , "wifiphotos.acoby.com::WifiLock");
        this.wifiLock.acquire();

        try {
            Log.v(LOGTAG,"Starting HTTP server");
            this.httpServer = new HttpServer(this);
            this.httpServer.start();
        } catch(Exception e) {
            Log.v(LOGTAG, Log.getStackTraceString(e));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (wakeLock != null) {
            wakeLock.release();
        }

        if (wifiLock != null) {
            wifiLock.release();
        }

        if (this.httpServer != null) {
            this.httpServer.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Log.v(LOGTAG,"Permissions not granted");
                if (this.httpServer != null) {
                    this.httpServer.stop();
                }
                this.finish();
            }
        }
    }

    private void ensurePermissionsGranted() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (this.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.v(LOGTAG,"Requesting permission READ_EXTERNAL_STORAGE");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
            }

            if (this.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.v(LOGTAG,"Requesting permission WRITE_EXTERNAL_STORAGE");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 200);
            }
        } else {
            Log.v(LOGTAG,"Permissions READ_EXTERNAL_STORAGE and WRITE_EXTERNAL_STORAGE automatically granted because SDK was older than version 23");
        }
    }
}
