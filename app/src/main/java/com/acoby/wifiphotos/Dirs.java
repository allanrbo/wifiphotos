package com.acoby.wifiphotos;

import android.os.Environment;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

import static android.os.Environment.isExternalStorageRemovable;

public class Dirs {
    AppCompatActivity activity;

    public Dirs(AppCompatActivity activity) {
        this.activity = activity;
    }

    // Copied from https://developer.android.com/topic/performance/graphics/cache-bitmap
    // Creates a unique subdirectory of the designated app cache directory. Tries to use external
    // but if not mounted, falls back on internal storage.
    public File getCacheDir(String uniqueName) {
        // Check if media is mounted or storage is built-in, if so, try and use external cache dir
        // otherwise use internal cache dir
        boolean useExternal = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) || !isExternalStorageRemovable();
        final String cachePath = useExternal ? this.activity.getExternalCacheDir().getPath() : this.activity.getCacheDir().getPath();
        File dir = new File(cachePath + File.separator + uniqueName);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public File getFilesDir(String uniqueName) {
        boolean useExternal = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) || !isExternalStorageRemovable();
        final String filesPath = useExternal ? this.activity.getExternalFilesDir(null).getPath() : this.activity.getFilesDir().getPath();
        File dir = new File(filesPath + File.separator + uniqueName);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
}
