package com.acoby.wifiphotos;

import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

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
import java.util.List;
import java.util.Map;

import static android.database.Cursor.FIELD_TYPE_FLOAT;
import static android.database.Cursor.FIELD_TYPE_INTEGER;
import static android.database.Cursor.FIELD_TYPE_STRING;

public class Trash {
    AppCompatActivity activity;
    Cache cache;
    Dirs dirs;
    Gson gson;

    public Trash(AppCompatActivity activity, Cache cache, Dirs dirs) {
        this.activity = activity;
        this.cache = cache;
        this.dirs = dirs;

        this.gson = new Gson();
    }

    public List<HttpServer.Image> getImageIDsInTrash() {
        File dir = this.dirs.getFilesDir("trash");
        List images = new ArrayList<HttpServer.Image>();
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
                    }
                }

                images.add(new HttpServer.Image(id, dateTaken, dateModified, size, name, width, height));
            }
        }

        Collections.sort(images, (o1, o2) -> -Long.compare(((HttpServer.Image)o1).dateTaken, ((HttpServer.Image)o2).dateTaken));

        return images;
    }

    public void moveImageToTrash(long imageID) throws Exception {
        Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageID);

        File trashDir = this.dirs.getFilesDir("trash");
        File trashFile = new File(trashDir + "/" + imageID + ".jpeg");
        File trashMetaDataFile = new File(trashFile + ".json");
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

        OutputStream out = new FileOutputStream(trashMetaDataFile);
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
            try {
                Log.v(MainActivity.TAG, "Falling back to stream copying image " + imageID + " rather than file system move");
                InputStream in = this.activity.getContentResolver().openInputStream(contentUri);
                out = new FileOutputStream(trashFile);
                Trash.copyStream(in, out);
                out.close();
                in.close();
            } catch(Exception e) {
                trashMetaDataFile.delete();
                trashFile.delete();
                throw e;
            }
        }

        if (!trashFile.exists()) {
            throw new Exception("failed to create trash file");
        }

        if (!trashMetaDataFile.exists()) {
            throw new Exception("failed to create meta data trash file");
        }

        this.activity.getContentResolver().delete(contentUri, null, null);
    }

    public void deleteImageFromTrash(long imageID) throws IOException {
        File dir = this.dirs.getFilesDir("trash");

        File file = new File(dir + "/" + imageID + ".jpeg");
        if (file.exists()) {
            file.delete();
        }

        File metaDataFile = new File(dir + "/" + imageID + ".jpeg.json");
        if (metaDataFile.exists()) {
            metaDataFile.delete();
        }

        this.cache.deleteCache(imageID);
    }

    public void restoreImageFromTrash(long imageID) throws Exception {
        File trashDir = this.dirs.getFilesDir("trash");
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
        if (contentUri == null) {
            throw new Exception("failed to insert meta data");
        }

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
            try {
                Log.v(MainActivity.TAG, "Falling back to stream copying image " + imageID + " rather than file system move");
                OutputStream out = this.activity.getContentResolver().openOutputStream(contentUri);
                InputStream in = new FileInputStream(trashFile);
                Trash.copyStream(in, out);
                in.close();
                out.close();
            } catch (Exception e) {
                // We failed to move the actual JPEG from trash to the collection, so we will remove the meta data again.
                if (trashFile.exists()) {
                    this.activity.getContentResolver().delete(contentUri, null, null);
                }
                throw e;
            }
        }

        // Delete image and meta data file from trash directory now that it has been restored.
        if (trashFile.exists()) {
            trashFile.delete();
        }
        if (metaDataFile.exists()) {
            metaDataFile.delete();
        }
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
