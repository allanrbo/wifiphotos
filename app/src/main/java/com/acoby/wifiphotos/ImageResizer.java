package com.acoby.wifiphotos;

import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ImageResizer {
    AppCompatActivity activity;
    RenderScript renderScript;
    MessageDigest md5;
    File cacheDir;

    public static final int QUALITY_HIGH = 1;
    public static final int QUALITY_LOW = 2;

    public ImageResizer(AppCompatActivity activity) {
        this.activity = activity;
        this.renderScript = RenderScript.create(this.activity);

        try {
            this.md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            // This should never happen, as MD5 is hardcoded in the above.
            throw new RuntimeException(e);
        }

        this.cacheDir = this.activity.getExternalCacheDir();
        if (this.cacheDir == null) {
            this.cacheDir = this.activity.getCacheDir();
        }
    }

    public File getResizedImageFile(long imageID, boolean isTrash, int size) throws IOException {
        // Calculate cache file name
        long lastModified = this.getImageLastModified(imageID);
        String cacheFilePath = this.generateCacheFilePath(imageID, lastModified, size);
        File cacheFile = new File(cacheFilePath);

        if (!cacheFile.exists()) {
            // TODO avoid this locking.
            // The lock is here as a workaround for out-of-memory when testing on a OnePlus X with many images loading concurrently:
            //   java.lang.OutOfMemoryError: Failed to allocate a 51916812 byte allocation with 16769248 free bytes and 28MB until OOM
            synchronized (this) {
                for(int retries = 0; ; retries++) {
                    try {
                        long before = System.currentTimeMillis();

                        InputStream in = this.openOrigImage(imageID, isTrash);
                        ByteBuffer jpegData = ByteBuffer.allocateDirect(in.available());
                        Channels.newChannel(in).read(jpegData);

                        this.resize(jpegData, cacheFile, size);

                        Log.v(MainActivity.TAG, "Resized image " + imageID + " in " + (System.currentTimeMillis() - before) + "ms");
                        break;
                    } catch (FileNotFoundException e) {
                        throw e;
                    } catch (Throwable e) { // Throwable instead of Exception to also get OutOfMemoryError
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
            }
        }

        return cacheFile;
    }

    private InputStream openOrigImage(long imageID, boolean isTrash) throws FileNotFoundException {
        if (isTrash) {
            File dir = this.activity.getExternalFilesDir("trash");
            return new FileInputStream(dir + "/" + imageID + ".jpeg");
        }

        Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageID);
        return this.activity.getContentResolver().openInputStream(contentUri);
    }

    private String generateCacheFilePath(long imageID, long imageLastModified, int size) {
        md5.update((imageID + ",").getBytes());
        md5.update((imageLastModified + ",").getBytes());
        md5.update((size + ",").getBytes());
        byte[] md5digest = md5.digest();

        StringBuilder sb = new StringBuilder(md5digest.length * 2);
        for (byte b : md5digest) {
            sb.append(String.format("%02x", b));
        }
        String hash = sb.toString();

        return this.cacheDir + "/" + hash;
    }

    private long getImageLastModified(long imageID) {
        // Query the Android MediaStore API.
        String[] projection = new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_MODIFIED};
        String selection = MediaStore.Images.Media._ID + " == ?";
        String[] selectionArgs = {imageID + ""};
        String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";
        Cursor cur = this.activity.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder);

        long lastModified = 0;
        if (cur.moveToNext()) {
            lastModified = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED));
        }
        cur.close();
        return lastModified;
    }

    private void resize(ByteBuffer in, File cacheFile, int size) throws IOException {
        // Based on https://medium.com/@petrakeas/alias-free-resize-with-renderscript-5bf15a86ce3

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

        float resizeRatio = (float) srcWidth / dstWidth;

        // Calculate gaussian's radius
        // https://android.googlesource.com/platform/frameworks/rs/+/master/cpu_ref/rsCpuIntrinsicBlur.cpp
        float sigma = resizeRatio / (float) Math.PI;
        float radius = 2.5f * sigma - 1.5f;
        radius = Math.min(25, Math.max(0.0001f, radius));

        // Gaussian filter
        Allocation tmpIn = Allocation.createFromBitmap(this.renderScript, src);
        Allocation tmpFiltered = Allocation.createTyped(this.renderScript, tmpIn.getType());
        ScriptIntrinsicBlur blurIntrinsic = ScriptIntrinsicBlur.create(this.renderScript, tmpIn.getElement());
        blurIntrinsic.setRadius(radius);
        blurIntrinsic.setInput(tmpIn);
        blurIntrinsic.forEach(tmpFiltered);
        if (!src.isRecycled()) {
            src.recycle();
        }
        tmpIn.destroy();
        blurIntrinsic.destroy();

        // Resize
        Type t = Type.createXY(this.renderScript, tmpFiltered.getElement(), dstWidth, dstHeight);
        Allocation tmpOut = Allocation.createTyped(this.renderScript, t);
        ScriptIntrinsicResize resizeIntrinsic = ScriptIntrinsicResize.create(this.renderScript);
        resizeIntrinsic.setInput(tmpFiltered);
        resizeIntrinsic.forEach_bicubic(tmpOut);
        Bitmap dst = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888);
        tmpOut.copyTo(dst);

        tmpFiltered.destroy();
        tmpOut.destroy();
        resizeIntrinsic.destroy();

        ByteBuffer outBuffer = LibjpegTurbo.compress(dst, dstWidth, dstHeight);

        OutputStream out = new FileOutputStream(cacheFile);
        Channels.newChannel(out).write(outBuffer);
        out.close();

        if (!dst.isRecycled()) {
            dst.recycle();
        }
    }
}
