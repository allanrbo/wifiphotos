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
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    public static String TAG = "WifiPhotos";

    private HttpServer httpServer;
    private ImageResizer imageResizer;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock1;
    private WifiManager.WifiLock wifiLock2;

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

        // Keeping screen on because app only runs HTTP server when screen is on. This is for security, to decrease the chance of running the server accidentally.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Taking wake lock. This is because the HTTP server will do CPU intensive work (image resizing) for the remote client, even though the app screen is not being used interactively.
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        this.wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"wifiphotos.acoby.com::Wakelock");
        this.wakeLock.acquire();

        // Taking Wi-Fi lock. This is to ensure best HTTP server transfer rates.
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.wifiLock1 = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF , "wifiphotos_wifilock_highperf");
        this.wifiLock1.acquire();
        this.wifiLock2 = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "wifiphotos_wifilock_lowlatency");
        this.wifiLock2.acquire();

        try {
            this.imageResizer = new ImageResizer(this);

            if (DebugFeatures.BIND_ANY_INTERFACE) {
                ipAddress = null; // To bind to any interface and not just the Wi-Fi.
            }

            if (ip != 0 || DebugFeatures.BIND_ANY_INTERFACE) {
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

        if (this.imageResizer != null) {
            this.imageResizer.close();
            this.imageResizer = null;
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
