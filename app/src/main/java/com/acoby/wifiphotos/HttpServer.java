package com.acoby.wifiphotos;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Process;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

import static android.database.Cursor.FIELD_TYPE_FLOAT;
import static android.database.Cursor.FIELD_TYPE_INTEGER;
import static android.database.Cursor.FIELD_TYPE_STRING;

public class HttpServer extends NanoHTTPD {
    AppCompatActivity activity;
    Gson gson;
    ImageResizer imageResizer;

    HashSet<String> allowedTokens = new HashSet<>();
    boolean loginInProgress = false;

    Pattern apiBucketContentsRegex = Pattern.compile("/api/buckets/([0-9-]+|trash)");
    Pattern apiImageRegex = Pattern.compile("/api/images/(trash/)?([0-9-]+)");

    Response apiNotFoundResponse;
    Response htmlNotFoundResponse;

    boolean threadPrioritySet = false;

    public HttpServer(AppCompatActivity activity, ImageResizer imageResizer) throws IOException {
        super(8080);
        this.activity = activity;
        this.gson = new Gson();
        this.imageResizer = imageResizer;

        this.apiNotFoundResponse = this.newJsonMsgResponse(Response.Status.NOT_FOUND, "error", "Not found");
        this.htmlNotFoundResponse = this.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "Not found");
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (!threadPrioritySet) {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
        }

        try {
            String uri = session.getUri();
            Log.v(MainActivity.TAG, "Got " + session.getMethod() + " request with URI " + uri);

            if (uri.startsWith("/api/")) {
                return this.addCorsHeaders(this.serveApi(session));
            }

            return this.addCorsHeaders(this.serveStaticFiles(session));
        } catch(Exception e) {
            Log.v(MainActivity.TAG, Log.getStackTraceString(e));
            return this.newJsonMsgResponse( Response.Status.INTERNAL_ERROR, "error", "Internal error: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private Response addNoCacheHeaders(Response r) {
        r.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        r.addHeader("Pragma", "no-cache");
        r.addHeader("Expires", "0");
        return r;
    }

    private Response addCorsHeaders(Response r) {
        if (DebugFeatures.COMPILE_WITH_DEBUG_FEATURES) {
            r.addHeader("Access-Control-Allow-Origin", "*");
            r.addHeader("Access-Control-Max-Age", "86400");
            r.addHeader("Access-Control-Allow-Methods", "*");
            r.addHeader("Access-Control-Allow-Headers", "*, Authorization");
        }
        return r;
    }

    static class Buckets {
        public String displayName;
    }

    private Response serveApi(IHTTPSession session) {
        String uri = session.getUri();

        String body = null;
        if (session.getMethod() == Method.POST) {
            // Read the request body.
            // It is important to always read the request body if there is one, even if we don't need it, or else on subsequent requests we will get a "java.net.SocketException: recvfrom failed: EBADF (Bad file number)".
            // This is most likely due to a bug in NanoHTTPD
            try {
                HashMap<String, String> map = new HashMap<String, String>();
                session.parseBody(map);
                body = map.get("postData");
            } catch (Exception e) {
                return this.newJsonMsgResponse(Response.Status.BAD_REQUEST, "error", "error while reading request body");
            }
        }

        // Access-Control headers (CORS) are received during OPTIONS requests.
        if (session.getMethod() == Method.OPTIONS) {
            return this.newJsonMsgResponse(Response.Status.OK, "ok", null);
        }

        /*
         * Login endpoint.
         */
        if (uri.equals("/api/login") && session.getMethod() == Method.POST) {
            synchronized (this) {
                // Was a login already in progress?
                if(this.loginInProgress) {
                    Log.v(MainActivity.TAG, "login already in progress");
                    return this.newJsonMsgResponse(Response.Status.OK, "deny", null);
                }
            }

            // Get request body, and get PIN and token from it.
            if (body == null || body.equals("")) {
                return this.newJsonMsgResponse(Response.Status.BAD_REQUEST, "error", "request body was empty");
            }
            Type stringStringMap = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> vals;
            try {
                vals = this.gson.fromJson(body, stringStringMap);
            } catch(Exception e) {
                return this.newJsonMsgResponse(Response.Status.BAD_REQUEST, "error", "request body parsing error");
            }
            if (!vals.containsKey("pin") || !vals.containsKey("token")) {
                return this.newJsonMsgResponse(Response.Status.BAD_REQUEST, "error", "request body did not contain expected fields");
            }

            BlockingQueue<String> blockingQueue = new LinkedBlockingQueue<String>();


            HttpServer thisHttpServer = this;
            this.activity.runOnUiThread(() -> {
                synchronized (thisHttpServer) {
                    this.loginInProgress = true;
                }

                View customView = this.activity.getLayoutInflater().inflate(R.layout.allow_access_popup, null);
                PopupWindow popupWindow = new PopupWindow(customView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

                TextView tv = customView.findViewById(R.id.connectionPinTxt);
                tv.setText("PIN: " + vals.get("pin"));

                customView.findViewById(R.id.allowBtn).setOnClickListener((View v) -> {
                    this.allowedTokens.add(vals.get("token"));
                    blockingQueue.add("allow");
                    synchronized (thisHttpServer) {
                        this.loginInProgress = false;
                    }
                    popupWindow.dismiss();
                });

                customView.findViewById(R.id.denyBtn).setOnClickListener((View v) -> {
                    blockingQueue.add("deny");
                    synchronized (thisHttpServer) {
                        this.loginInProgress = false;
                    }
                    popupWindow.dismiss();
                });

                popupWindow.showAtLocation(this.activity.findViewById(R.id.layout1), Gravity.CENTER, 0, 0);
            });

            try {
                String result = blockingQueue.take();
                Log.v(MainActivity.TAG, "Result from login request with PIN " + vals.get("pin") + ": " + result);
                for (String s : this.allowedTokens) {
                    Log.v(MainActivity.TAG, "Allowed token: " + s);
                }
                Response r = this.newJsonMsgResponse(Response.Status.OK, result, null);
                String setCookieVal = "authtoken=" + vals.get("token");
                r.addHeader("Set-Cookie", setCookieVal);
                return r;
            } catch(InterruptedException e) {
                Log.v(MainActivity.TAG, Log.getStackTraceString(e));
                return this.newJsonMsgResponse(Response.Status.INTERNAL_ERROR, "error", "Error while waiting for approve/deny");
            }
        }

        // All endpoints below require that requests are authenticated (unless compiled with debug features).
        if (!DebugFeatures.COMPILE_WITH_DEBUG_FEATURES) {
            String authToken = session.getCookies().read("authtoken");
            if (authToken == null) {
                return this.newJsonMsgResponse(Response.Status.FORBIDDEN, "unauthorized", "authtoken cookie required");
            }
            if (!this.allowedTokens.contains(authToken)) {
                return this.newJsonMsgResponse(Response.Status.FORBIDDEN, "unauthorized", "bad authtoken cookie");
            }
        }

        /*
         * Ping endpoint.
         */
        if (uri.equals("/api/ping")) {
            return this.newJsonMsgResponse(Response.Status.OK, "ok", null);
        }

        /*
         * Buckets list endpoint.
         */
        if (uri.equals("/api/buckets")) {
            List<HttpServer.Bucket> buckets = this.getAllBuckets();
            return this.addNoCacheHeaders(this.newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(buckets)));
        }

        /*
         * Images list endpoint.
         */
        Matcher m = this.apiBucketContentsRegex.matcher(uri);
        if (m.matches()) {
            if (m.group(1).equals("trash")) {
                List<Image> images = getImageIDsInTrash();
                return this.newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(images));
            }

            long bucketId;
            try {
                bucketId = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return this.apiNotFoundResponse;
            }

            List<Image> images = getImageIDs(bucketId);
            return this.addNoCacheHeaders(this.newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(images)));
        }

        /*
         * Image endpoint.
         */
        m = this.apiImageRegex.matcher(uri);
        if (m.matches()) {
            boolean isTrash = false;
            if (m.group(1) != null && m.group(1).equals("trash/")) {
                isTrash = true;
            }

            long imageID;
            try {
                imageID = Long.parseLong(m.group(2));
            } catch (NumberFormatException e) {
                return this.apiNotFoundResponse;
            }

            /*
             * Download image endpoint.
             */
            if (session.getMethod() == Method.GET) {
                boolean full = false;
                int size = 600;
                Map<String, List<String>> parameters = session.getParameters();
                if (parameters.containsKey("size")) {
                    String s = parameters.get("size").get(0);
                    if (s.equals("full")) {
                        full = true;
                    } else {
                        size = Integer.parseInt(s);
                    }
                }

                try {
                    if (full) {
                        InputStream in;
                        if (isTrash) {
                            File dir = this.activity.getExternalFilesDir("trash");
                            in = new FileInputStream(dir + "/" + imageID + ".jpeg");
                        } else {
                            Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageID);
                            in = this.activity.getContentResolver().openInputStream(contentUri);
                        }
                        return this.newChunkedResponse(Response.Status.OK, "image/jpeg", in);
                    }

                    InputStream inputStream = imageResizer.getResizedImageFile(imageID, isTrash, size);
                    return this.newChunkedResponse(Response.Status.OK, "image/jpeg", inputStream );
                } catch (IOException e) {
                    Log.v(MainActivity.TAG, Log.getStackTraceString(e));
                    return this.apiNotFoundResponse;
                }
            }

            /*
             * Delete image endpoint.
             */
            if (session.getMethod() == Method.DELETE) {
                if (isTrash) {
                    try {
                        this.deleteImageFromTrash(imageID);
                    } catch (Exception e) {
                        Log.v(MainActivity.TAG, Log.getStackTraceString(e));
                        return this.newJsonMsgResponse(Response.Status.INTERNAL_ERROR, "error", "Error while deleting image");
                    }
                } else {
                    try {
                        this.moveImageToTrash(imageID);
                    } catch (Exception e) {
                        Log.v(MainActivity.TAG, Log.getStackTraceString(e));
                        return this.newJsonMsgResponse(Response.Status.INTERNAL_ERROR, "error", "Error while moving image to trash");
                    }
                }

                return this.newJsonMsgResponse(Response.Status.OK, "ok", null);
            }

            /*
             * Restore image from trash endpoint.
             */
            if (session.getMethod() == Method.POST) {
                // Check that the request body action field says "restore".
                if (body == null || body.equals("")) {
                    return this.newJsonMsgResponse(Response.Status.BAD_REQUEST, "error", "request body was empty");
                }
                Type stringStringMap = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> vals;
                try {
                    vals = this.gson.fromJson(body, stringStringMap);
                } catch(Exception e) {
                    return this.newJsonMsgResponse(Response.Status.BAD_REQUEST, "error", "request body parsing error");
                }
                if (!vals.containsKey("action") || !vals.get("action").equals("restore")) {
                    return this.apiNotFoundResponse;
                }

                // Do the actual restore.
                try {
                    this.restoreImageFromTrash(imageID);
                    return this.newJsonMsgResponse(Response.Status.OK, "ok", null);
                } catch (Exception e) {
                    return this.newJsonMsgResponse(Response.Status.INTERNAL_ERROR, "error", "Error while restoring image from trash");
                }
            }
        }

        /*
         * Clear cache endpoint.
         */
        if (DebugFeatures.COMPILE_WITH_DEBUG_FEATURES) {
            if (uri.equals("/api/clearcache")) {
                if (session.getMethod() == Method.POST) {
                    try {
                        this.imageResizer.deleteCacheAll();
                    } catch(Exception e) {
                        Log.v(MainActivity.TAG, Log.getStackTraceString(e));
                    }
                    return this.addNoCacheHeaders(this.newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_HTML, "Resized photo cache cleared<br/><form method=\"post\"><input type=\"submit\" value=\"Clear resized photo cache\"/></form>"));
                } else {
                    return this.newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_HTML, "<form method=\"post\"><input type=\"submit\" value=\"Clear resized photo cache\"/></form>");
                }
            }
        }

        return this.apiNotFoundResponse;
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
                return this.htmlNotFoundResponse;
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
                return this.htmlNotFoundResponse;
            }

            InputStream inputStream = assetManager.open(assetPath);
            return this.newChunkedResponse(Response.Status.OK, this.getMimeTypeForFile(assetPath), inputStream);
        } catch (IOException e) {
            return this.htmlNotFoundResponse;
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
        cur.close();

        Collections.sort(allBuckets, (Bucket b1, Bucket b2) -> b1.displayName.compareTo(b2.displayName));

        // Sensible default in case this is a very vanilla phone without any buckets yet.
        if (allBuckets.size() == 0) {
            allBuckets.add(new Bucket("Camera", cameraBucketPath.toLowerCase().hashCode(), true));
        }

        return allBuckets;
    }

    public static class Image {
        public long imageId;
        public long dateTaken;
        public long dateModified;
        public long size;
        public String name;
        public int width;
        public int height;


        public Image(long imageId, long dateTaken, long dateModified, long size, String name, int width, int height) {
            this.imageId = imageId;
            this.dateTaken = dateTaken;
            this.dateModified = dateModified;
            this.size = size;
            this.name = name;
            this.width = width;
            this.height = height;
        }
    }

    private List<Image> getImageIDs(long bucketID) {
        // Query the Android MediaStore API.
        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
        };
        String selection = MediaStore.Images.Media.BUCKET_ID + " == ?";
        String[] selectionArgs = {bucketID + ""};
        String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";
        Cursor cur = this.activity.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder);

        int idIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
        int dateTakenIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
        int dateModifiedIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
        int sizeIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
        int displayNameIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
        int widthIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH);
        int heightIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT);

        List<Image> imageIDs = new ArrayList<Image>();
        while (cur.moveToNext()) {
            imageIDs.add(new Image(
                    cur.getLong(idIdx),
                    cur.getLong(dateTakenIdx),
                    cur.getLong(dateModifiedIdx),
                    cur.getLong(sizeIdx),
                    cur.getString(displayNameIdx),
                    cur.getInt(widthIdx),
                    cur.getInt(heightIdx)));
        }
        cur.close();

        return imageIDs;
    }

    private List<Image> getImageIDsInTrash() {
        File dir = this.activity.getExternalFilesDir("trash");
        List images = new ArrayList<Image>();
        for(String f : dir.list()) {
            if (f.endsWith(".jpeg")) {
                long id = Long.parseLong(f.replace(".jpeg", ""));
                long dateTaken = 0;
                long dateModified = 0;
                long size = 0;
                int width = 0;
                int height = 0;
                String name = "" + id;

                File metaDataFile = new File(dir + "/" + f + ".json");
                if (metaDataFile.exists()) {
                    try {
                        Reader r = new FileReader(metaDataFile);
                        Type stringStringMap = new TypeToken<Map<String, String>>(){}.getType();
                        Map<String,String> vals = this.gson.fromJson(r, stringStringMap);
                        r.close();

                        name = vals.get(MediaStore.Images.Media.DISPLAY_NAME);
                        dateTaken =  Long.parseLong(vals.get(MediaStore.Images.Media.DATE_TAKEN)) ;
                        dateModified = Long.parseLong(vals.get(MediaStore.Images.Media.DATE_MODIFIED));
                        size = Long.parseLong(vals.get(MediaStore.Images.Media.SIZE));
                        id = Long.parseLong(vals.get(MediaStore.Images.Media._ID));
                        width = Integer.parseInt(vals.get(MediaStore.Images.Media.WIDTH));
                        height = Integer.parseInt(vals.get(MediaStore.Images.Media.HEIGHT));
                    } catch (Exception e) {
                        Log.v(MainActivity.TAG, Log.getStackTraceString(e));

                        // TODO
                    }
                }

                images.add(new Image(id, dateTaken, dateModified, size, name, width, height));
            }
        }

        Collections.sort(images, (o1, o2) -> -Long.compare(((Image)o1).dateTaken, ((Image)o2).dateTaken));

        return images;
    }

    private void moveImageToTrash(long imageID) throws IOException {
        Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageID);

        File trashDir = this.activity.getExternalFilesDir("trash");
        File trashFile = new File(trashDir + "/" + imageID + ".jpeg");
        Log.v(MainActivity.TAG, "Moving to trash: " + trashFile);

        // Copy meta data to trash directory.
        Cursor cur = this.activity.getContentResolver().query(contentUri, null, null, null, null);
        cur.moveToFirst();
        Map<String,Object> vals = new HashMap<>();
        for (String colName : cur.getColumnNames()) {
            int colIdx = cur.getColumnIndex(colName);

            if (cur.getType(colIdx) == FIELD_TYPE_INTEGER) {
                vals.put(cur.getColumnName(colIdx), cur.getLong(colIdx));
            } else if (cur.getType(colIdx) == FIELD_TYPE_FLOAT) {
                vals.put(cur.getColumnName(colIdx), cur.getFloat(colIdx));
            } else if (cur.getType(colIdx) == FIELD_TYPE_STRING) {
                vals.put(cur.getColumnName(colIdx), cur.getString(colIdx));
            }
        }
        String filePath = null;
        try {
            filePath = cur.getString(cur.getColumnIndex(MediaStore.Images.Media.DATA));
        } catch(Exception e) {
        }
        cur.close();

        OutputStream out = new FileOutputStream(trashFile + ".json");
        out.write(this.gson.toJson(vals).getBytes());
        out.close();

        // Move actual JPEG to trash directory.
        // Try first to just move the file. This may stop working at some point, since the MediaStore.Images.Media.DATA is deprecated.
        boolean moveSuccess = false;
        try {
            if (filePath != null) {
                Log.v(MainActivity.TAG, "Doing file system move from " + filePath + " to " + trashFile);
                moveSuccess = (new File(filePath)).renameTo(trashFile);
            }
        } catch (Exception e) {
            Log.v(MainActivity.TAG, Log.getStackTraceString(e));
        }

        // Fall back to copying via streams if the file system move failed.
        if (!moveSuccess) {
            Log.v(MainActivity.TAG, "Falling back to stream copying image " + imageID + " rather than file system move");
            InputStream in = this.activity.getContentResolver().openInputStream(contentUri);
            out = new FileOutputStream(trashFile);
            HttpServer.copyStream(in, out);
            out.close();
            in.close();
        }

        this.activity.getContentResolver().delete(contentUri, null, null);
    }

    private void deleteImageFromTrash(long imageID) throws IOException {
        File dir = this.activity.getExternalFilesDir("trash");

        File file = new File(dir + "/" + imageID + ".jpeg");
        if (file.exists()) {
            file.delete();
        }

        File metaDataFile = new File(dir + "/" + imageID + ".jpeg.json");
        if (metaDataFile.exists()) {
            metaDataFile.delete();
        }

        this.imageResizer.deleteCache(imageID);
    }

    private void restoreImageFromTrash(long imageID) throws IOException {
        File trashDir = this.activity.getExternalFilesDir("trash");
        File trashFile = new File(trashDir + "/" + imageID + ".jpeg");
        Log.v(MainActivity.TAG, "Restoring from trash: " + trashFile);

        // Read meta data from meta data file in trash directory.
        Map<String,String> vals = new HashMap<>();
        File metaDataFile = new File(trashDir + "/" + imageID + ".jpeg.json");
        if (metaDataFile.exists()) {
            try {
                Reader r = new FileReader(metaDataFile);
                Type stringStringMap = new TypeToken<Map<String, String>>(){}.getType();
                vals = this.gson.fromJson(r, stringStringMap);
                r.close();
            } catch (Exception e) {
                // The meta data file was broken. Nothing we can do about this.
            }
        }

        // Prepare MediaStore meta data KV-pairs.
        ContentValues values = new ContentValues();
        for (Map.Entry<String, String> entry : vals.entrySet()) {
            values.put(entry.getKey(), entry.getValue());
        }

        // Insert meta data.
        Uri contentUri = this.activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        // Move actual JPEG out of trash directory.
        // Try first to just move the file. This may stop working at some point, since the MediaStore.Images.Media.DATA is deprecated.
        boolean moveSuccess = false;
        try {
            String filePath = vals.get(MediaStore.Images.Media.DATA);
            if (filePath != null) {
                Log.v(MainActivity.TAG, "Doing file system move from " + trashFile + " to " + filePath);
                moveSuccess = trashFile.renameTo(new File(filePath));
            }
        } catch (Exception e) {
            Log.v(MainActivity.TAG, Log.getStackTraceString(e));
        }

        // Fall back to copying via streams if the file system move failed.
        if (!moveSuccess) {
            Log.v(MainActivity.TAG, "Falling back to stream copying image " + imageID + " rather than file system move");
            OutputStream out = this.activity.getContentResolver().openOutputStream(contentUri);
            InputStream in = new FileInputStream(trashFile);
            HttpServer.copyStream(in, out);
            in.close();
            out.close();
        }

        // Delete image and meta data file from trash directory now that it has been restored.
        if (trashFile.exists()) {
            trashFile.delete();
        }
        if (metaDataFile.exists()) {
            metaDataFile.delete();
        }
    }

    private Response newJsonMsgResponse(Response.IStatus httpCode, String status, String msg) {
        Map<String,String> m = new HashMap<>();
        m.put("status", status);
        if (msg != null) {
            m.put("message", msg);
        }
        return this.newFixedLengthResponse(httpCode, "application/json", this.gson.toJson(m));
    }

    // https://stackoverflow.com/a/22128215/40645
    private static long copyStream(InputStream from, OutputStream to) throws IOException {
        byte[] buf = new byte[0x1000]; // 4K
        long total = 0;
        while (true) {
            int r = from.read(buf);
            if (r == -1) {
                break;
            }
            to.write(buf, 0, r);
            total += r;
        }
        return total;
    }
}
