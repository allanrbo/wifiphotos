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
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    public static String TAG = "WifiPhotos";

    private HttpServer httpServer;
    private ImageResizer imageResizer;

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

        // Display IP address in phone UI.
        WifiManager wifiMgr = (WifiManager) this.getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
        int ip = wifiInfo.getIpAddress();
        String ipAddress = Formatter.formatIpAddress(ip);
        TextView instruction1Txt = findViewById(R.id.instruction1);
        TextView instruction2Txt = findViewById(R.id.instruction2);
        TextView ipAddrTxt = findViewById(R.id.ipAddr);
        if (ip == 0) {
            instruction1Txt.setVisibility(View.GONE);
            instruction2Txt.setVisibility(View.GONE);
            ipAddrTxt.setText("Please connect device to Wi-Fi.");
        } else {
            instruction1Txt.setVisibility(View.VISIBLE);
            instruction2Txt.setVisibility(View.VISIBLE);
            ipAddrTxt.setText("http://" + ipAddress + ":8080");
        }

        // Remove the graphic in landscape mode, to ensure room for the text.
        ImageView imageView = findViewById(R.id.imageView);
        int orientation = getResources().getConfiguration().orientation;
        if (Configuration.ORIENTATION_LANDSCAPE == orientation) {
            imageView.setVisibility(View.GONE);
        } else {
            imageView.setVisibility(View.VISIBLE);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        this.wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"wifiphotos.acoby.com::Wakelock");
        this.wakeLock.acquire();

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF , "wifiphotos.acoby.com::WifiLock");
        this.wifiLock.acquire();

        try {
            this.imageResizer = new ImageResizer(this);

            if (DebugFeatures.COMPILE_WITH_DEBUG_FEATURES) {
                ipAddress = null; // To bind to any interface and not just the Wi-Fi.
            }

            if (ip != 0 || DebugFeatures.COMPILE_WITH_DEBUG_FEATURES) {
                Log.v(TAG, "Starting HTTP server");
                this.httpServer = new HttpServer(this, this.imageResizer, ipAddress);
                this.httpServer.start();
            }
        } catch(Exception e) {
            Log.v(TAG, Log.getStackTraceString(e));
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

        if (this.imageResizer != null) {
            this.imageResizer.close();
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
                Log.v(TAG,"Permissions not granted");
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
                Log.v(TAG,"Requesting permission READ_EXTERNAL_STORAGE");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
            }

            if (this.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.v(TAG,"Requesting permission WRITE_EXTERNAL_STORAGE");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 200);
            }
        } else {
            Log.v(TAG,"Permissions READ_EXTERNAL_STORAGE and WRITE_EXTERNAL_STORAGE automatically granted because SDK was older than version 23");
        }
    }
}
