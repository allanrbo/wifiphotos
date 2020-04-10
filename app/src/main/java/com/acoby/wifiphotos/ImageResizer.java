package com.acoby.wifiphotos;

import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    public File getResizedImageFile(long imageID, int size, int quality) throws IOException {
        long before = System.currentTimeMillis();

        Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageID);

        // Get source image dimensions.
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        InputStream in = this.activity.getContentResolver().openInputStream(contentUri);
        BitmapFactory.decodeStream(in, null, options);
        in.close();
        int srcWidth = options.outWidth;
        int srcHeight = options.outHeight;

        // Calculate new dimensions.
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

        // Calculate cache file name
        long lastModified = this.getImageLastModified(imageID);
        String cacheFilePath = this.generateCacheFilePath(imageID, lastModified, size, quality);
        File cacheFile = new File(cacheFilePath);

        if (!cacheFile.exists()) {
            // TODO avoid this locking.
            // The lock is here as a workaround for out-of-memory when testing on a OnePlus X with many images loading concurrently:
            //   java.lang.OutOfMemoryError: Failed to allocate a 51916812 byte allocation with 16769248 free bytes and 28MB until OOM
            synchronized (this) {
                if (quality == QUALITY_LOW) {
                    this.resizeLowQuality(contentUri, cacheFile, srcWidth, srcHeight, dstWidth, dstHeight);
                } else {
                    this.resizeHighQuality(contentUri, cacheFile, srcWidth, srcHeight, dstWidth, dstHeight);
                }
            }
        }

        Log.v(MainActivity.LOGTAG, "getResizedImageFile time taken: " + (System.currentTimeMillis() - before));
        return cacheFile;
    }

    private String generateCacheFilePath(long imageID, long imageLastModified, int size, int quality) {
        md5.update((imageID + ",").getBytes());
        md5.update((imageLastModified + ",").getBytes());
        md5.update((size + ",").getBytes());
        md5.update((quality + ",").getBytes());
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

    private void resizeLowQuality(Uri contentUri, File cacheFile, int srcWidth, int srcHeight, int dstWidth, int dstHeight) throws IOException {
        // Based on https://stackoverflow.com/a/4250279/40645

        long before = System.currentTimeMillis();

        // Calculate the correct inSampleSize/scale value. This helps reduce memory use. It should be a power of 2
        // from: https://stackoverflow.com/questions/477572/android-strange-out-of-memory-issue/823966#823966
        int inSampleSize = 1;
        while (srcWidth / 2 > dstWidth) {
            srcWidth /= 2;
            srcHeight /= 2;
            inSampleSize *= 2;
        }

        float desiredScale = (float) dstWidth / srcWidth;

        // Decode with inSampleSize
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = false;
        options.inDither = false;
        options.inSampleSize = inSampleSize;
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream in = this.activity.getContentResolver().openInputStream(contentUri);
        Bitmap sampledSrcBitmap = BitmapFactory.decodeStream(in, null, options);
        in.close();

        // Resize
        Matrix matrix = new Matrix();
        matrix.postScale(desiredScale, desiredScale);
        Bitmap scaledBitmap = Bitmap.createBitmap(sampledSrcBitmap, 0, 0, srcWidth, srcHeight, matrix, true);
        if (!sampledSrcBitmap.isRecycled()) {
            sampledSrcBitmap.recycle();
        }

        // Save
        OutputStream out = new RemoveFFE2OutputStreamDecorator(new FileOutputStream(cacheFile));
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 50, out);
        if (!scaledBitmap.isRecycled()) {
            scaledBitmap.recycle();
        }
        out.close();
    }

    private void resizeHighQuality(Uri contentUri, File cacheFile, int srcWidth, int srcHeight, int dstWidth, int dstHeight) throws IOException {
        // Based on https://medium.com/@petrakeas/alias-free-resize-with-renderscript-5bf15a86ce3

        float resizeRatio = (float) srcWidth / dstWidth;

        // Calculate gaussian's radius
        // https://android.googlesource.com/platform/frameworks/rs/+/master/cpu_ref/rsCpuIntrinsicBlur.cpp
        float sigma = resizeRatio / (float) Math.PI;
        float radius = 2.5f * sigma - 1.5f;
        radius = Math.min(25, Math.max(0.0001f, radius));

        InputStream in = this.activity.getContentResolver().openInputStream(contentUri);
        Bitmap src = BitmapFactory.decodeStream(in);
        in.close();
        Bitmap.Config bitmapConfig = src.getConfig();

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

        OutputStream out = new RemoveFFE2OutputStreamDecorator(new FileOutputStream(cacheFile));
        dst.compress(Bitmap.CompressFormat.JPEG, 80, out);
        if (!dst.isRecycled()) {
            dst.recycle();
        }
        out.close();
    }

    // Some image viewer applications (such as Windows Photo Viewer) doesn't seem to like the ICC profile meta data that Android's Bitmap.compress writes.
    // This decorator removes the section.
    private static class RemoveFFE2OutputStreamDecorator extends OutputStream {
        OutputStream underlyingStream;
        boolean marker = false;
        boolean skipSegment = false;

        public RemoveFFE2OutputStreamDecorator(OutputStream underlyingStream) {
            this.underlyingStream = underlyingStream;
        }

        @Override
        public void write(int b) throws IOException {
            // Based on https://en.wikipedia.org/wiki/JPEG#Syntax_and_structure
            if (this.marker) {
                this.marker = false;
                if ((b & 0xFF) == 0xE2) { // The 0xFF,0xE2 segment that Android writes seems to cause trouble with Windows Photo Viewer.
                    this.skipSegment = true;
                } else {
                    this.skipSegment = false;
                    this.underlyingStream.write(0xFF);
                    this.underlyingStream.write(b);
                }
            } else if ((b & 0xFF) == 0xFF) {
                this.marker = true;
            } else if (!this.skipSegment) {
                this.underlyingStream.write(b);
            }
        }

        @Override
        public void flush() throws IOException {
            this.underlyingStream.flush();
        }

        @Override
        public void close() throws IOException {
            this.underlyingStream.close();
        }
    }
}
