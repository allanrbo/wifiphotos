package com.acoby.wifiphotos;

import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.MediaStore;
import android.renderscript.Allocation;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.renderscript.ScriptIntrinsicResize;
import android.renderscript.Type;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class ImageResizer {
    RenderScript renderScript;
    AppCompatActivity activity;

    Semaphore semaphore;
    AtomicInteger concurrentCount = new AtomicInteger(0);

    Cache cache;
    Dirs dirs;

    Gson gson;

    public ImageResizer(AppCompatActivity activity, Cache cache, Dirs dirs) throws IOException {
        this.activity = activity;
        this.cache = cache;
        this.dirs = dirs;

        this.gson = new Gson();

        this.renderScript = RenderScript.create(this.activity);

        int maxConcurrency = Runtime.getRuntime().availableProcessors();
        this.semaphore = new Semaphore(maxConcurrency, true);
    }

    public InputStream getResizedImageFile(long imageID, boolean isTrash, int size) throws IOException {
        // Try first to get from cache.
        InputStream in = this.cache.tryGetCache(imageID, isTrash, size);
        if (in != null) {
            return in;
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

                in = this.openOrigImage(imageID, isTrash);
                ByteBuffer jpegData = ByteBuffer.allocateDirect(in.available());
                Channels.newChannel(in).read(jpegData);
                in.close();

                int orientation = this.getOrientation(imageID, isTrash);

                resizedImage = this.resize(jpegData, orientation, size);

                Log.v(MainActivity.TAG, "Resized image " + imageID + " in " + (System.currentTimeMillis() - before) + "ms");
                break;
            } catch (FileNotFoundException e) {
                if (in != null) {
                    in.close();
                }
                this.concurrentCount.decrementAndGet();
                this.semaphore.release();
                throw e;
            } catch (OutOfMemoryError e) { // Throwable instead of Exception to also get OutOfMemoryError
                if (in != null) {
                    in.close();
                }

                if (retries++ > 5) {
                    this.concurrentCount.decrementAndGet();
                    this.semaphore.release();
                    throw e;
                }

                // Lower the max concurrency to one less than the current number, unless this is the only current thread.
                Log.v(MainActivity.TAG, "OutOfMemoryError on thread " + Thread.currentThread().getId() + " while resizing " + this.concurrentCount.get()  + " images concurrently. Attempting to lower concurrency and retry.");
                this.semaphore.drainPermits();
                if (this.concurrentCount.get() > 1) {
                    this.semaphore.acquireUninterruptibly();
                    Log.v(MainActivity.TAG, "OutOfMemoryError follow up: Acquired another semaphore on thread " + Thread.currentThread().getId() + ", which will lower concurrency by 1 going forward.");
                } else {
                    Log.v(MainActivity.TAG, "OutOfMemoryError follow up: Thread " + Thread.currentThread().getId() + " was the only thread that was resizing. Ensured concurrency is max 1 going forward.");
                }
            } catch (Exception e) {
                if (in != null) {
                    in.close();
                }

                if (retries++ > 5) {
                    this.concurrentCount.decrementAndGet();
                    this.semaphore.release();

                    try {
                        // As a last resort, try just returning the original image.
                        return this.openOrigImage(imageID, isTrash);
                    } catch (Exception e2) {
                        throw e;
                    }
                }

                Log.v(MainActivity.TAG, "Got exception while resizing image " + imageID);
                Log.v(MainActivity.TAG, Log.getStackTraceString(e));
                Log.v(MainActivity.TAG, "Retrying in 500ms");
                try {
                    Thread.sleep(500);
                } catch(InterruptedException e2) {
                }
            }
        }

        this.concurrentCount.decrementAndGet();
        this.semaphore.release();

        this.cache.store(imageID, isTrash, size, resizedImage);

        return new ByteBufferBackedInputStream(resizedImage);
    }

    private int[] calcNewDimensions(int srcWidth, int srcHeight, int dstSize) {
        int dstWidth;
        int dstHeight;
        if (srcWidth > srcHeight) {
            if (srcWidth > dstSize) {
                dstWidth = dstSize;
                dstHeight = (srcHeight * dstSize) / srcWidth;
            } else {
                dstWidth = srcWidth;
                dstHeight = srcHeight;
            }
        } else {
            if (srcHeight > dstSize) {
                dstWidth = (srcWidth * dstSize) / srcHeight;
                dstHeight = dstSize;
            } else {
                dstWidth = srcWidth;
                dstHeight = srcHeight;
            }
        }

        return new int[] {dstWidth, dstHeight};
    }

    public InputStream openOrigImage(long imageID, boolean isTrash) throws FileNotFoundException {
        if (isTrash) {
            File dir = this.dirs.getFilesDir("trash");
            return new FileInputStream(dir + "/" + imageID + ".jpeg");
        }

        InputStream in;
        Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageID);

        // It appears to be significantly faster to use FileInputStream rather than getContentResolver().openInputStream(), even though this is deprecated.
        // openInputStream() sometimes takes several seconds.
        // Therefore try FileInputStream first and fall back to openInputStream().
        try {
            Cursor cur = this.activity.getContentResolver().query(contentUri, null, null, null, null);
            cur.moveToFirst();
            String filePath = null;
            try {
                filePath = cur.getString(cur.getColumnIndex(MediaStore.Images.Media.DATA));
            } catch(Exception e) {
            }
            cur.close();
            in = new FileInputStream(filePath);
        } catch (Exception e) {
            in = this.activity.getContentResolver().openInputStream(contentUri);
        }

        return in;
    }

    private ByteBuffer resize(ByteBuffer in, int orientation, int size) {
        Bitmap src = LibjpegTurbo.decompress(in);

        if (orientation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(orientation);
            Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, false);
            if (!src.isRecycled()) {
                src.recycle();
            }
            src = rotated;
        }

        int[] dstSize = this.calcNewDimensions(src.getWidth(), src.getHeight(), size);
        Bitmap dst = resizeBitmap2(src, dstSize[0]);
        ByteBuffer outBuffer = LibjpegTurbo.compress(dst);

        if (!src.isRecycled()) {
            src.recycle();
        }

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

    private int getOrientation(long imageID, boolean isTrash) {
        if (isTrash) {
            File trashDir = this.dirs.getFilesDir("trash");

            // Read meta data from meta data file in trash directory.
            Map<String,String> vals = new HashMap<>();
            File metaDataFile = new File(trashDir + "/" + imageID + ".jpeg.json");
            if (metaDataFile.exists()) {
                try {
                    Reader r = new FileReader(metaDataFile);
                    java.lang.reflect.Type stringStringMap = new TypeToken<Map<String, String>>(){}.getType();
                    vals = this.gson.fromJson(r, stringStringMap);
                    r.close();
                } catch (Exception e) {
                    // The meta data file was broken. Nothing we can do about this.
                }
            }

            if (vals == null || !vals.containsKey(MediaStore.Images.Media.ORIENTATION)) {
                return 0;
            }

            return Integer.parseInt(vals.get(MediaStore.Images.Media.ORIENTATION));
        }

        Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageID);
        Cursor cur = this.activity.getContentResolver().query(contentUri, null, null, null, null);
        cur.moveToFirst();
        int orientation = 0;
        try {
            orientation = cur.getInt(cur.getColumnIndex(MediaStore.Images.Media.ORIENTATION));
        } catch(Exception e) {
        }
        cur.close();

        return orientation;
    }
}
