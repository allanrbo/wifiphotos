package com.acoby.wifiphotos;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.Environment;
import android.provider.MediaStore;
import android.renderscript.Allocation;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.renderscript.ScriptIntrinsicResize;
import android.renderscript.Type;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static android.os.Environment.isExternalStorageRemovable;

public class ImageResizer {
    RenderScript renderScript;
    AppCompatActivity activity;

    Semaphore semaphore;
    AtomicInteger concurrentCount = new AtomicInteger(0);

    final static long cacheMaxSize = 50*1024*1024; // 200MiB

    private DiskLruCache diskLruCache;

    public ImageResizer(AppCompatActivity activity) throws IOException {
        this.activity = activity;

        this.renderScript = RenderScript.create(this.activity);

        File cacheDir = this.getDiskCacheDir("wifiphotoscache");
        Log.v(MainActivity.TAG, "Cache dir: " + cacheDir.toString());
        this.diskLruCache = DiskLruCache.open(cacheDir, 1,1, ImageResizer.cacheMaxSize);

        int maxConcurrency = Runtime.getRuntime().availableProcessors();
        this.semaphore = new Semaphore(maxConcurrency, true);
    }

    public void close() {
        try {
            this.diskLruCache.close();
        } catch(Exception e) {
        }
    }

    public InputStream getResizedImageFile(long imageID, boolean isTrash, int size) throws IOException {
        // Try first to get from cache.
        long origFileSize = this.getImageSize(imageID, isTrash);
        String cacheKey = imageID + "-" + origFileSize + "-" + size;
        DiskLruCache.Snapshot snapshot = this.diskLruCache.get(cacheKey);
        if (snapshot != null) {
            return snapshot.getInputStream(0);
        }

        // Limit how many images can concurrently be resizing, in order to not hit OutOfMemoryError.
        if (!this.semaphore.tryAcquire()) {
            Log.v(MainActivity.TAG, "Currently resizing " + this.concurrentCount.get() + " images. Waiting to acquire semaphore to resize image " + imageID);
            this.semaphore.acquireUninterruptibly();
        }
        this.concurrentCount.incrementAndGet();

        ByteBuffer resizedImage = null;

        // Retry-loop for in case we hit OutOfMemoryError or other transient problems while resizing.
        for(int retries = 0; ; retries++) {
            try {
                long before = System.currentTimeMillis();

                InputStream in = this.openOrigImage(imageID, isTrash);
                ByteBuffer jpegData = ByteBuffer.allocateDirect(in.available());
                Channels.newChannel(in).read(jpegData);
                in.close();

                resizedImage = this.resize(jpegData, size);

                Log.v(MainActivity.TAG, "Resized image " + imageID + " in " + (System.currentTimeMillis() - before) + "ms");
                break;
            } catch (FileNotFoundException e) {
                throw e;
            } catch (OutOfMemoryError e) { // Throwable instead of Exception to also get OutOfMemoryError
                Log.v(MainActivity.TAG, "OutOfMemoryError on thread " + Thread.currentThread().getId() + " while resizing " + this.concurrentCount.get()  + " images concurrently. Attempting to lower concurrency and retry.");

                // Lower the max concurrency to one less than the current number, unless this is the only current thread.
                this.semaphore.drainPermits();
                if (this.concurrentCount.get() > 1) {
                    this.semaphore.acquireUninterruptibly();
                    Log.v(MainActivity.TAG, "OutOfMemoryError follow up: Acquired another semaphore on thread " + Thread.currentThread().getId() + ", which will lower concurrency by 1 going forward.");
                } else {
                    Log.v(MainActivity.TAG, "OutOfMemoryError follow up: Thread " + Thread.currentThread().getId() + " was the only thread that was resizing. Ensured concurrency is max 1 going forward.");
                }
            } catch (Exception e) {
                Log.v(MainActivity.TAG, "Got exception while resizing image " + imageID);
                Log.v(MainActivity.TAG, Log.getStackTraceString(e));
                if (retries++ > 5) {
                    throw e;
                }
                Log.v(MainActivity.TAG, "Retrying");
                try {
                    Thread.sleep(500);
                } catch(InterruptedException e2) {
                }
            }
        }

        this.concurrentCount.decrementAndGet();
        this.semaphore.release();

        DiskLruCache.Editor editor = this.diskLruCache.edit(cacheKey);
        OutputStream out = editor.newOutputStream(0);
        Channels.newChannel(out).write(resizedImage);
        out.close();
        editor.commit();

        resizedImage.rewind();
        return new ByteBufferBackedInputStream(resizedImage);
    }

    private InputStream openOrigImage(long imageID, boolean isTrash) throws FileNotFoundException {
        if (isTrash) {
            File dir = this.activity.getExternalFilesDir("trash");
            return new FileInputStream(dir + "/" + imageID + ".jpeg");
        }

        // Initially tried to use the more correct getContentResolver().openInputStream(contentUri) here, but it sometimes hangs for takes several seconds.

        // Query the Android MediaStore API.
        String[] projection = new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA};
        String selection = MediaStore.Images.Media._ID + " == ?";
        String[] selectionArgs = {imageID + ""};
        String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";
        Cursor cur = this.activity.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder);
        String fileName = null;
        if (cur.moveToFirst()) {
            fileName = cur.getString(cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATA));
        }
        cur.close();

        if (fileName == null) {
            throw new FileNotFoundException();
        }

        return new FileInputStream(fileName);
    }

    private long getImageSize(long imageID, boolean isTrash) {
        if (isTrash) {
            File dir = this.activity.getExternalFilesDir("trash");
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

    private ByteBuffer resize(ByteBuffer in, int size) {
        Bitmap src = LibjpegTurbo.decompress(in);

        // Calculate new dimensions.
        int srcWidth = src.getWidth();
        int srcHeight = src.getHeight();
        int dstWidth;
        int dstHeight;
        if (srcWidth > srcHeight) {
            if (srcWidth > size) {
                dstWidth = size;
                dstHeight = (srcHeight * size) / srcWidth;
            } else {
                dstWidth = srcWidth;
                dstHeight = srcHeight;
            }
        } else {
            if (srcHeight > size) {
                dstWidth = (srcWidth * size) / srcHeight;
                dstHeight = size;
            } else {
                dstWidth = srcWidth;
                dstHeight = srcHeight;
            }
        }

        Bitmap dst = resizeBitmap2(src, dstWidth);

        ByteBuffer outBuffer = LibjpegTurbo.compress(dst, dstWidth, dstHeight);

        if (!dst.isRecycled()) {
            dst.recycle();
        }

        return outBuffer;
    }

    // Renderscript use is based on https://medium.com/@petrakeas/alias-free-resize-with-renderscript-5bf15a86ce3
    private Bitmap resizeBitmap2(Bitmap src, int dstWidth) {
        Bitmap.Config  bitmapConfig = src.getConfig();
        int srcWidth = src.getWidth();
        int srcHeight = src.getHeight();
        float srcAspectRatio = (float) srcWidth / srcHeight;
        int dstHeight = (int) (dstWidth / srcAspectRatio);

        float resizeRatio = (float) srcWidth / dstWidth;

        // Calculate gaussian's radius
        float sigma = resizeRatio / (float) Math.PI;
        // https://android.googlesource.com/platform/frameworks/rs/+/master/cpu_ref/rsCpuIntrinsicBlur.cpp
        float radius = 2.5f * sigma - 1.5f;
        radius = Math.min(25, Math.max(0.0001f, radius));

        // Gaussian filter
        Allocation tmpIn = Allocation.createFromBitmap(this.renderScript, src, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SHARED);
        Allocation tmpFiltered = Allocation.createTyped(this.renderScript, tmpIn.getType());
        ScriptIntrinsicBlur blurInstrinsic = ScriptIntrinsicBlur.create(this.renderScript, tmpIn.getElement());

        blurInstrinsic.setRadius(radius);
        blurInstrinsic.setInput(tmpIn);
        blurInstrinsic.forEach(tmpFiltered);

        tmpIn.destroy();
        blurInstrinsic.destroy();

        // Resize
        Bitmap dst = Bitmap.createBitmap(dstWidth, dstHeight, bitmapConfig);
        Type t = Type.createXY(this.renderScript, tmpFiltered.getElement(), dstWidth, dstHeight);
        Allocation tmpOut = Allocation.createTyped(this.renderScript, t);
        ScriptIntrinsicResize resizeIntrinsic = ScriptIntrinsicResize.create(this.renderScript);

        resizeIntrinsic.setInput(tmpFiltered);
        resizeIntrinsic.forEach_bicubic(tmpOut);
        tmpOut.copyTo(dst);

        tmpFiltered.destroy();
        tmpOut.destroy();
        resizeIntrinsic.destroy();

        return dst;
    }

    public void deleteCache(long imageId) throws IOException {
        for (File f : this.diskLruCache.getDirectory().listFiles()) {
            String name = f.getName();
            if (name.startsWith(imageId + "-")) {
                String key = f.getName().replaceAll("\\.\\d+$", "");
                Log.v(MainActivity.TAG, key);
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
        File cacheDir = this.getDiskCacheDir("wifiphotoscache");
        this.diskLruCache = DiskLruCache.open(cacheDir, 1,1, cacheMaxSize);
    }

    // Creates a unique subdirectory of the designated app cache directory. Tries to use external
    // but if not mounted, falls back on internal storage.
    public File getDiskCacheDir(String uniqueName) {
        // Check if media is mounted or storage is built-in, if so, try and use external cache dir
        // otherwise use internal cache dir
        boolean useExternal = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) || !isExternalStorageRemovable();
        final String cachePath = useExternal ? this.activity.getExternalCacheDir().getPath() : this.activity.getCacheDir().getPath();
        return new File(cachePath + File.separator + uniqueName);
    }
}
