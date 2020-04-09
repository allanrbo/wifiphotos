package com.acoby.wifiphotos;

import android.content.ContentUris;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;

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
                    md5.update((this.getImageLastModified(imageId)+",").getBytes());
                    md5.update((size+",").getBytes());
                    String hash = this.byteArrayToHex(md5.digest());
                    cacheFile = new File(cacheDir + "/" + hash);


                } catch (Exception e) {
                    Log.v(MainActivity.LOGTAG, "Failed to get cache directory. Exception: " + Log.getStackTraceString(e));
                    return this.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_HTML, "Internal server error");
                }
                Log.v(MainActivity.LOGTAG, "Cache file: " + cacheFile.toString());

                if (!cacheFile.exists()) {
                    // TODO avoid this locking.
                    // The lock is here as a workaround for out-of-memory when testing on a OnePlus X with many images loading concurrently:
                    //   java.lang.OutOfMemoryError: Failed to allocate a 51916812 byte allocation with 16769248 free bytes and 28MB until OOM
                    synchronized (this) {
                        Log.v(MainActivity.LOGTAG, "imageId: " + imageId + ", calling openInputStream(" + contentUri.toString() + ")");
                        InputStream in = this.activity.getContentResolver().openInputStream(contentUri);
                        Log.v(MainActivity.LOGTAG, "imageId: " + imageId + ", calling BitmapFactory.decodeStream");
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

                        Log.v(MainActivity.LOGTAG, "imageId: " + imageId + ", calling Bitmap.createScaledBitmap");
                        Bitmap sbm = Bitmap.createScaledBitmap(bm, newWidth, newHeight, true);
                        if (!bm.isRecycled()) {
                            bm.recycle();
                        }
                        FileOutputStream f = new FileOutputStream(cacheFile);
                        Log.v(MainActivity.LOGTAG,  "imageId: " + imageId + ", calling Bitmap.CompressFormat");
                        sbm.compress(Bitmap.CompressFormat.JPEG, 80, f);
                        f.close();
                        if (!sbm.isRecycled()) {
                            sbm.recycle();
                        }
                    }
                }

                FileInputStream f = new FileInputStream (cacheFile);
                return this.newChunkedResponse(Response.Status.OK, "image/jpeg", f);

            } catch (NumberFormatException e) {
                return this.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "Not found");
            } catch (IOException e) {
                return this.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "Not found");
            }
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
        String[] projection = new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DISPLAY_NAME };
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

        if(cur.moveToNext()) {
            return cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED));
        }

        return 0;
    }


    private static String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for(byte b: a) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
