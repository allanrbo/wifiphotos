package com.acoby.wifiphotos;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.provider.MediaStore;
import android.renderscript.Allocation;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.renderscript.ScriptIntrinsicResize;
import android.renderscript.Type;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class ImageResizer {
    RenderScript renderScript;
    AppCompatActivity activity;
    File cacheDir;

    Semaphore semaphore;
    AtomicInteger concurrentCount = new AtomicInteger(0);

    public ImageResizer(AppCompatActivity activity) {
        this.activity = activity;

        this.renderScript = RenderScript.create(this.activity);

        this.cacheDir = this.activity.getExternalCacheDir();
        if (this.cacheDir == null) {
            this.cacheDir = this.activity.getCacheDir();
        }

        int maxConcurrency = Runtime.getRuntime().availableProcessors();
        this.semaphore = new Semaphore(maxConcurrency, true);
        Log.v(MainActivity.TAG, "Initial max concurrency when resizing images: " + maxConcurrency);
    }

    public File getResizedImageFile(long imageID, boolean isTrash, int size) throws IOException {
        // Calculate cache file name
        long lastModified = this.getImageLastModified(imageID);
        String cacheFilePath = this.cacheDir + "/" + imageID + "," + lastModified + "," + size;
        File cacheFile = new File(cacheFilePath);

        if (!cacheFile.exists()) {
            // Limit how many images can concurrently be resizing, in order to not hit OutOfMemoryError.
            if (!this.semaphore.tryAcquire()) {
                Log.v(MainActivity.TAG, "Currently resizing " + this.concurrentCount.get() + " images. Waiting to acquire semaphore to resize image " + imageID);
                this.semaphore.acquireUninterruptibly();
            }
            this.concurrentCount.incrementAndGet();

            // Retry-loop for in case we hit OutOfMemoryError or other transient problems while resizing.
            for(int retries = 0; ; retries++) {
                try {
                    long before = System.currentTimeMillis();
                    InputStream in = this.openOrigImage(imageID, isTrash);
                    ByteBuffer jpegData = ByteBuffer.allocateDirect(in.available());
                    Channels.newChannel(in).read(jpegData);
                    in.close();
                    this.resize(jpegData, cacheFile, size);
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
        }

        return cacheFile;
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

    private long getImageLastModified(long imageID) {
        // Query the Android MediaStore API.
        String[] projection = new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_MODIFIED};
        String selection = MediaStore.Images.Media._ID + " == ?";
        String[] selectionArgs = {imageID + ""};
        String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";
        Cursor cur = this.activity.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder);

        long lastModified = 0;
        if (cur.moveToFirst()) {
            lastModified = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED));
        }
        cur.close();
        return lastModified;
    }

    private void resize(ByteBuffer in, File cacheFile, int size) throws IOException {
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

        Bitmap dst = resizeBitmap2(this.renderScript, src, dstWidth);

        ByteBuffer outBuffer = LibjpegTurbo.compress(dst, dstWidth, dstHeight);

        OutputStream out = new FileOutputStream(cacheFile);
        Channels.newChannel(out).write(outBuffer);
        out.close();

        if (!dst.isRecycled()) {
            dst.recycle();
        }
    }

    // Renderscript use is based on https://medium.com/@petrakeas/alias-free-resize-with-renderscript-5bf15a86ce3
    private static Bitmap resizeBitmap2(RenderScript rs, Bitmap src, int dstWidth) {
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
        Allocation tmpIn = Allocation.createFromBitmap(rs, src, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SHARED);
        Allocation tmpFiltered = Allocation.createTyped(rs, tmpIn.getType());
        ScriptIntrinsicBlur blurInstrinsic = ScriptIntrinsicBlur.create(rs, tmpIn.getElement());

        blurInstrinsic.setRadius(radius);
        blurInstrinsic.setInput(tmpIn);
        blurInstrinsic.forEach(tmpFiltered);

        tmpIn.destroy();
        blurInstrinsic.destroy();

        // Resize
        Bitmap dst = Bitmap.createBitmap(dstWidth, dstHeight, bitmapConfig);
        Type t = Type.createXY(rs, tmpFiltered.getElement(), dstWidth, dstHeight);
        Allocation tmpOut = Allocation.createTyped(rs, t);
        ScriptIntrinsicResize resizeIntrinsic = ScriptIntrinsicResize.create(rs);

        resizeIntrinsic.setInput(tmpFiltered);
        resizeIntrinsic.forEach_bicubic(tmpOut);
        tmpOut.copyTo(dst);

        tmpFiltered.destroy();
        tmpOut.destroy();
        resizeIntrinsic.destroy();

        return dst;
    }
}
