package com.acoby.wifiphotos;

import android.database.Cursor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;

public class Cache {
    AppCompatActivity activity;
    Dirs dirs;
    DiskLruCache diskLruCache;

    final static long cacheMaxSize = 200*1024*1024; // 200MiB

    public Cache(AppCompatActivity activity, Dirs dirs) {
        this.activity = activity;
        this.dirs = dirs;

        if (!DebugFeatures.DISABLE_CACHING) {
            File cacheDir = this.dirs.getCacheDir("wifiphotoscache");
            Log.v(MainActivity.TAG, "Cache dir: " + cacheDir.toString());
            try {
                this.diskLruCache = DiskLruCache.open(cacheDir, 1, 1, Cache.cacheMaxSize);
            } catch(Exception e) {
                Log.v(MainActivity.TAG, "Failed to initialize DiskLruCache");
                Log.v(MainActivity.TAG, Log.getStackTraceString(e));
                this.diskLruCache = null;
            }
        }
    }

    public InputStream tryGetCache(Long imageId, boolean isTrash, int size) throws IOException {
        String cacheKey = this.getCacheKey(imageId, isTrash, size);

        if (!DebugFeatures.DISABLE_CACHING) {
            DiskLruCache.Snapshot snapshot = this.diskLruCache.get(cacheKey);
            if (snapshot != null) {
                return snapshot.getInputStream(0);
            }
        }

        return null;
    }

    public void store(Long imageId, boolean isTrash, int size, ByteBuffer data) throws IOException {
        String cacheKey = this.getCacheKey(imageId, isTrash, size);

        if (this.diskLruCache != null && !DebugFeatures.DISABLE_CACHING) {
            OutputStream out = null;
            try {
                DiskLruCache.Editor editor = this.diskLruCache.edit(cacheKey);
                out = editor.newOutputStream(0);
                Channels.newChannel(out).write(data);
                out.close();
                editor.commit();
            } catch (Exception e) {
                Log.v(MainActivity.TAG, "Failed to write to DiskLruCache");
                Log.v(MainActivity.TAG, Log.getStackTraceString(e));
                if (out != null) {
                    out.close();
                }
            }
        }

    }

    public void deleteCache(long imageId) throws IOException {
        if (this.diskLruCache == null) {
            return;
        }

        for (File f : this.diskLruCache.getDirectory().listFiles()) {
            String name = f.getName();
            if (name.startsWith(imageId + "-")) {
                String key = f.getName().replaceAll("\\.\\d+$", "");
                DiskLruCache.Snapshot snapshot = this.diskLruCache.get(key);
                if (snapshot != null) {
                    DiskLruCache.Editor editor = snapshot.edit();
                    editor.set(0, "");
                    editor.commit();
                }
            }
        }
    }

    public void deleteCacheAll() throws IOException {
        this.diskLruCache.delete();
        File cacheDir = this.dirs.getCacheDir("wifiphotoscache");
        this.diskLruCache = DiskLruCache.open(cacheDir, 1,1, cacheMaxSize);
    }

    public String getCacheKey(long imageID, boolean isTrash, int size) {
        long origFileSize = this.getImageFileSize(imageID, isTrash);
        return imageID + "-" + origFileSize + "-" + size + "-v2";
    }

    public void close() {
        try {
            this.diskLruCache.close();
        } catch(Exception e) {
        }
    }

    private long getImageFileSize(long imageID, boolean isTrash) {
        if (isTrash) {
            File dir = this.dirs.getFilesDir("trash");
            File f = new File(dir + "/" + imageID + ".jpeg");
            return f.length();
        }

        // Query the Android MediaStore API.
        String[] projection = new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE};
        String selection = MediaStore.Images.Media._ID + " == ?";
        String[] selectionArgs = {imageID + ""};
        String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";
        Cursor cur = this.activity.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder);

        long size = 0;
        if (cur.moveToFirst()) {
            size= cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE));
        }
        cur.close();
        return size;
    }
}
