package com.acoby.wifiphotos;

import android.content.ContentUris;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.renderscript.Allocation;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.renderscript.ScriptIntrinsicResize;
import android.renderscript.Type;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.gson.Gson;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

public class HttpServer extends NanoHTTPD {
    AppCompatActivity activity;

    Pattern apiBucketContentsRegex = Pattern.compile("/api/buckets/([0-9-]+)");
    Pattern apiImageRegex = Pattern.compile("/api/images/([0-9-]+)");

    public HttpServer(AppCompatActivity activity) {
        super(8080);
        this.activity = activity;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Log.v(MainActivity.LOGTAG, "Got request with URI " + uri);

        if (uri.startsWith("/api/")) {
            return this.addCorsHeaders(this.serveApi(session));
        }

        return this.addCorsHeaders(this.serveStaticFiles(session));
    }


    private Response addNoCacheHeaders(Response r) {
        r.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        r.addHeader("Pragma", "no-cache");
        r.addHeader("Expires", "0");
        return r;
    }

    private Response addCorsHeaders(Response r) {
        // TODO remove these insecure settings for prod
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Max-Age", "86400");
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "X-Requested-With");
        r.addHeader("Access-Control-Allow-Headers", "Authorization");
        return r;
    }

    static class Buckets {
        public String displayName;
    }

    private Response serveApi(IHTTPSession session) {
        String uri = session.getUri();

        Gson gson = new Gson();

        /*
         * Buckets list endpoint.
         */
        if (uri.equals("/api/buckets")) {
            List<HttpServer.Bucket> buckets = this.getAllBuckets();
            return this.addNoCacheHeaders(this.newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(buckets)));
        }

        /*
         * Images within a bucket list endpoint.
         */
        Matcher m = this.apiBucketContentsRegex.matcher(uri);
        if (m.matches()) {
            try {
                long bucketId = Long.parseLong(m.group(1));
                List<Image> images = getImageIDs(bucketId);
                return this.addNoCacheHeaders(this.newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(images)));
            } catch (NumberFormatException e) {
                return this.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "Not found");
            }
        }

        /*
         * Image download endpoint.
         */
        m = this.apiImageRegex.matcher(uri);
        if (m.matches()) {
            try {
                long imageId = Long.parseLong(m.group(1));

                int size = 500;
                Map<String, List<String>> parameters = session.getParameters();
                if (parameters.containsKey("size")) {
                    size = Integer.parseInt(parameters.get("size").get(0));
                }

                Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId);

                File cacheDir = this.activity.getExternalCacheDir();
                if (cacheDir == null) {
                    cacheDir = this.activity.getCacheDir();
                }

                File cacheFile;
                try {
                    MessageDigest md5 = MessageDigest.getInstance("MD5");
                    md5.update(contentUri.toString().getBytes());
                    md5.update((this.getImageLastModified(imageId) + ",").getBytes());
                    md5.update((size + ",").getBytes());
                    String hash = this.byteArrayToHex(md5.digest());
                    cacheFile = new File(cacheDir + "/" + hash);
                } catch (Exception e) {
                    Log.v(MainActivity.LOGTAG, "Failed to get cache directory. Exception: " + Log.getStackTraceString(e));
                    return this.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_HTML, "Internal server error");
                }
                Log.v(MainActivity.LOGTAG, "Cache file: " + cacheFile.toString());

                //if (!cacheFile.exists()) {
                if (1==1) {
                    // TODO avoid this locking.
                    // The lock is here as a workaround for out-of-memory when testing on a OnePlus X with many images loading concurrently:
                    //   java.lang.OutOfMemoryError: Failed to allocate a 51916812 byte allocation with 16769248 free bytes and 28MB until OOM
                    synchronized (this) {
                        int technique = 5;
                        if (parameters.containsKey("technique")) {
                            technique = Integer.parseInt(parameters.get("technique").get(0));
                        }
                        if (technique == 1) resize1(contentUri, cacheFile, size);
                        if (technique == 2) resize2(contentUri, cacheFile, size);
                        if (technique == 3) resize3(contentUri, cacheFile, size);
                        if (technique == 4) resize4(contentUri, (int)imageId, cacheFile, size);
                        if (technique == 5) resize5(contentUri, cacheFile, size);
                    }
                }

                FileInputStream f = new FileInputStream(cacheFile);
                return this.newChunkedResponse(Response.Status.OK, "image/jpeg", f);

            } catch (NumberFormatException e) {
                return this.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "Not found");
            } catch (IOException e) {
                Log.v(MainActivity.LOGTAG, Log.getStackTraceString(e));
                return this.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "Not found");
            }
        }

        /*
         * Clear cache endpoint.
         */
        if (uri.equals("/api/clearcache")) {
            File cacheDir = this.activity.getExternalCacheDir();
            for(File f : cacheDir .listFiles()) {
                try {
                    Log.v(MainActivity.LOGTAG, "Deleting " + f.toString());
                    f.delete();
                } catch (Exception e) {
                    Log.v(MainActivity.LOGTAG, Log.getStackTraceString(e));
                }
            }

            cacheDir = this.activity.getCacheDir();
            for(File f : cacheDir .listFiles()) {
                try {
                    Log.v(MainActivity.LOGTAG, "Deleting " + f.toString());
                    f.delete();
                } catch (Exception e) {
                    Log.v(MainActivity.LOGTAG, Log.getStackTraceString(e));
                }
            }

            return this.addNoCacheHeaders(this.newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_HTML, "Resized photo cache cleared"));
        }

        return this.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "Not found");
    }

    private Response serveStaticFiles(IHTTPSession session) {
        try {
            String uri = session.getUri();
            if (uri.equals("/")) {
                uri = "/index.html";
            }

            String assetPath = "www" + uri;

            // Disallow traversing up the file tree.
            if (assetPath.contains("..")) {
                return this.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "Not found");
            }

            AssetManager assetManager = this.activity.getAssets();

            // Does the file exist in the assets directory?
            File f = new File(assetPath);
            boolean found = false;
            for (String fileListItem : activity.getAssets().list(f.getParent())) {
                if (f.getName().equals(fileListItem)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                return this.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "Not found");
            }

            InputStream inputStream = assetManager.open(assetPath);
            return this.newChunkedResponse(Response.Status.OK, this.getMimeTypeForFile(assetPath), inputStream);
        } catch (IOException e) {
            return this.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "Not found");
        }
    }

    private void resize1(Uri contentUri, File cacheFile, int size) throws IOException {
        Log.v(MainActivity.LOGTAG, "resize1");
        long before = System.currentTimeMillis();

        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling openInputStream");
        InputStream in = this.activity.getContentResolver().openInputStream(contentUri);
        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling BitmapFactory.decodeStream");
        Bitmap bm = BitmapFactory.decodeStream(in);
        in.close();
        int origWidth = bm.getWidth();
        int origHeight = bm.getHeight();
        int newWidth = (origWidth * size) / origHeight;
        int newHeight = size;
        if (origWidth > origHeight) {
            newWidth = size;
            newHeight = (origHeight * size) / origWidth;
        }

        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling Bitmap.createScaledBitmap");
        Bitmap sbm = Bitmap.createScaledBitmap(bm, newWidth, newHeight, true);
        if (!bm.isRecycled()) {
            bm.recycle();
        }
        FileOutputStream f = new FileOutputStream(cacheFile);
        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling Bitmap.CompressFormat");
        sbm.compress(Bitmap.CompressFormat.JPEG, 100, f);
        f.close();
        if (!sbm.isRecycled()) {
            sbm.recycle();
        }

        Log.v(MainActivity.LOGTAG, "Time taken: " + (System.currentTimeMillis() - before));
    }

    private void resize2(Uri contentUri, File cacheFile, int size) throws IOException {
        Log.v(MainActivity.LOGTAG, "resize2");
        long before = System.currentTimeMillis();

        // Based on https://stackoverflow.com/a/4250279/40645
        // Get the source image's dimensions
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling openInputStream");
        InputStream in = this.activity.getContentResolver().openInputStream(contentUri);
        BitmapFactory.decodeStream(in, null, options);
        in.close();

        int srcWidth = options.outWidth;
        int srcHeight = options.outHeight;

        // Only scale if the source is big enough. This code is just trying to fit a image into a certain width.
        if (size > srcWidth)
            size = srcWidth;


        // Calculate the correct inSampleSize/scale value. This helps reduce memory use. It should be a power of 2
        // from: https://stackoverflow.com/questions/477572/android-strange-out-of-memory-issue/823966#823966
        int inSampleSize = 1;
        while (srcWidth / 2 > size) {
            srcWidth /= 2;
            srcHeight /= 2;
            inSampleSize *= 2;
        }

        float desiredScale = (float) size / srcWidth;

        // Decode with inSampleSize
        options.inJustDecodeBounds = false;
        options.inDither = false;
        options.inSampleSize = inSampleSize;
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        in = this.activity.getContentResolver().openInputStream(contentUri);
        Bitmap sampledSrcBitmap = BitmapFactory.decodeStream(in, null, options);
        in.close();

        // Resize
        Matrix matrix = new Matrix();
        matrix.postScale(desiredScale, desiredScale);
        Bitmap scaledBitmap = Bitmap.createBitmap(sampledSrcBitmap, 0, 0, sampledSrcBitmap.getWidth(), sampledSrcBitmap.getHeight(), matrix, true);
        if (!sampledSrcBitmap.isRecycled()) {
            sampledSrcBitmap.recycle();
        }
        sampledSrcBitmap = null;

        // Save
        FileOutputStream out = new FileOutputStream(cacheFile);
        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling Bitmap.CompressFormat");
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
        out.close();
        if (!scaledBitmap.isRecycled()) {
            scaledBitmap.recycle();
        }
        scaledBitmap = null;

        Log.v(MainActivity.LOGTAG, "Time taken: " + (System.currentTimeMillis() - before));
    }

    private void resize3(Uri contentUri, File cacheFile, int size) throws IOException {
        Log.v(MainActivity.LOGTAG, "resize3");
        long before = System.currentTimeMillis();

        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling openInputStream");
        InputStream in = this.activity.getContentResolver().openInputStream(contentUri);
        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling BitmapFactory.decodeStream");
        Bitmap bm = BitmapFactory.decodeStream(in);
        in.close();
        int origWidth = bm.getWidth();
        int origHeight = bm.getHeight();
        int newWidth = (origWidth * size) / origHeight;
        int newHeight = size;
        if (origWidth > origHeight) {
            newWidth = size;
            newHeight = (origHeight * size) / origWidth;
        }

        FileOutputStream out = new FileOutputStream(cacheFile);
        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling Bitmap.CompressFormat");
        try {
            Glide.with(this.activity).asBitmap().load(contentUri).submit(newWidth, newHeight).get().compress(Bitmap.CompressFormat.JPEG, 100, out);
        } catch (Exception e) {
            Log.v(MainActivity.LOGTAG, Log.getStackTraceString(e));
        }

        Log.v(MainActivity.LOGTAG, "Time taken: " + (System.currentTimeMillis() - before));
    }

    private void resize4(Uri contentUri, long imageID, File cacheFile, int size) throws IOException {
        InputStream in2 = this.activity.getContentResolver().openInputStream(contentUri);
        File cacheDir = this.activity.getExternalCacheDir();
        File tmp = new File(cacheDir + "/tmp.jpg");
        FileOutputStream out2 = new FileOutputStream(tmp);
        byte[] buf = new byte[1024];
        int len;
        while((len=in2.read(buf))>0){
            out2.write(buf,0,len);
        }
        out2.close();
        in2.close();


        Log.v(MainActivity.LOGTAG, "resize4");
        long before = System.currentTimeMillis();

        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling openInputStream");
        InputStream in = this.activity.getContentResolver().openInputStream(contentUri);
        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling BitmapFactory.decodeStream");
        Bitmap bm = BitmapFactory.decodeStream(in);
        in.close();
        int origWidth = bm.getWidth();
        int origHeight = bm.getHeight();
        int newWidth = (origWidth * size) / origHeight;
        int newHeight = size;
        if (origWidth > origHeight) {
            newWidth = size;
            newHeight = (origHeight * size) / origWidth;
        }

        FileOutputStream out = new FileOutputStream(cacheFile);
        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling Bitmap.CompressFormat");
        try {
            Picasso.get().load(tmp).resize(newWidth,newHeight).onlyScaleDown().get().compress(Bitmap.CompressFormat.JPEG, 100, out);
        } catch (Exception e) {
            Log.v(MainActivity.LOGTAG, Log.getStackTraceString(e));
        }

        Log.v(MainActivity.LOGTAG, "Time taken: " + (System.currentTimeMillis() - before));
    }

    private void resize5(Uri contentUri, File cacheFile, int size) throws IOException {
        Log.v(MainActivity.LOGTAG, "resize5");
        long before = System.currentTimeMillis();

        RenderScript rs = RenderScript.create(this.activity);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        Log.v(MainActivity.LOGTAG, "contentUri: " + contentUri + ", calling openInputStream");
        InputStream in = this.activity.getContentResolver().openInputStream(contentUri);
        BitmapFactory.decodeStream(in, null, options);
        in.close();


        int srcWidth = options.outWidth;
        int srcHeight = options.outHeight;

        int dstWidth = (srcWidth * size) / srcHeight;
        int dstHeight = size;
        if (srcWidth > srcHeight) {
            dstWidth = size;
            dstHeight = (srcHeight * size) / srcWidth;
        }

        float resizeRatio = (float) srcWidth / dstWidth;

        /* Calculate gaussian's radius */
        float sigma = resizeRatio / (float) Math.PI;
        // https://android.googlesource.com/platform/frameworks/rs/+/master/cpu_ref/rsCpuIntrinsicBlur.cpp
        float radius = 2.5f * sigma - 1.5f;
        radius = Math.min(25, Math.max(0.0001f, radius));


        in = this.activity.getContentResolver().openInputStream(contentUri);
        Bitmap src = BitmapFactory.decodeStream(in);
        in.close();

        Bitmap.Config  bitmapConfig = src.getConfig();


        /* Gaussian filter */
        Allocation tmpIn = Allocation.createFromBitmap(rs, src);
        Allocation tmpFiltered = Allocation.createTyped(rs, tmpIn.getType());
        ScriptIntrinsicBlur blurInstrinsic = ScriptIntrinsicBlur.create(rs, tmpIn.getElement());

        blurInstrinsic.setRadius(radius);
        blurInstrinsic.setInput(tmpIn);
        blurInstrinsic.forEach(tmpFiltered);

        tmpIn.destroy();
        blurInstrinsic.destroy();

        /* Resize */
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

        FileOutputStream out = new FileOutputStream(cacheFile);
        dst.compress(Bitmap.CompressFormat.JPEG, 100, out);

        Log.v(MainActivity.LOGTAG, "Time taken: " + (System.currentTimeMillis() - before));
    }

    public static class Bucket {
        public String displayName;
        public long id;
        public boolean isCameraBucket;

        public Bucket(String displayName, long id, boolean isCameraBucket) {
            this.displayName = displayName;
            this.id = id;
            this.isCameraBucket = isCameraBucket;
        }
    }

    private List<Bucket> getAllBuckets() {
        // Query the Android MediaStore API.
        String[] projection = new String[]{MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME};
        String selection = "";
        String[] selectionArgs = {};
        String sortOrder = "";
        Cursor cur = this.activity.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder);

        int bucketIdIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID);
        int bucketDisplayNameIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);

        // This is how the Android's default gallery app gets the camera bucket ID: https://android.googlesource.com/platform/packages/apps/Gallery2/+/refs/heads/master/src/com/android/gallery3d/util/MediaSetUtils.java#33
        String cameraBucketPath = Environment.getExternalStorageDirectory().toString() + "/DCIM/Camera";
        long cameraBucketId = cameraBucketPath.toLowerCase().hashCode();

        List<Bucket> allBuckets = new ArrayList<Bucket>();
        Set s = new HashSet<Long>();
        while (cur.moveToNext()) {
            long id = cur.getLong(bucketIdIdx);
            if (s.contains(id)) {
                continue;
            }

            String displayName = cur.getString(bucketDisplayNameIdx);

            allBuckets.add(new Bucket(displayName, id, id == cameraBucketId));
            s.add(id);
        }

        Collections.sort(allBuckets, (Bucket b1, Bucket b2) -> b1.displayName.compareTo(b2.displayName));
        return allBuckets;
    }

    public static class Image {
        public long imageId;
        public long dateTaken;
        public String name;

        public Image(long imageId, long dateTaken, String name) {
            this.imageId = imageId;
            this.dateTaken = dateTaken;
            this.name = name;
        }
    }

    private List<Image> getImageIDs(long bucketID) {
        // Query the Android MediaStore API.
        String[] projection = new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DISPLAY_NAME};
        String selection = MediaStore.Images.Media.BUCKET_ID + " == ?";
        String[] selectionArgs = {bucketID + ""};
        String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";
        Cursor cur = this.activity.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder);

        int idIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
        int dateTakenIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
        int displayNameIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);

        List<Image> imageIDs = new ArrayList<Image>();
        while (cur.moveToNext()) {
            imageIDs.add(new Image(cur.getLong(idIdx), cur.getLong(dateTakenIdx), cur.getString(displayNameIdx)));
        }

        return imageIDs;
    }

    private long getImageLastModified(long imageID) {
        // Query the Android MediaStore API.
        String[] projection = new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_MODIFIED};
        String selection = MediaStore.Images.Media._ID + " == ?";
        String[] selectionArgs = {imageID + ""};
        String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";
        Cursor cur = this.activity.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder);

        if (cur.moveToNext()) {
            return cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED));
        }

        return 0;
    }


    private static String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte b : a) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
