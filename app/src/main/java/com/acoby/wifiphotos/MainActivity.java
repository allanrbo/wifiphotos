package com.acoby.wifiphotos;

import android.Manifest;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        isReadStoragePermissionGranted();
        isWriteStoragePermissionGranted();

        try {
            HttpServer s = new HttpServer();
            s.start();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

    }

    static String TAG = "sometag1";

    public  boolean isReadStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                Log.v(TAG,"Permission is granted1");
                return true;
            } else {

                Log.v(TAG,"Permission is revoked1");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 3);
                return false;
            }
        }
        else { //permission is automatically granted on sdk<23 upon installation
            Log.v(TAG,"Permission is granted1");
            return true;
        }
    }

    public  boolean isWriteStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                Log.v(TAG,"Permission is granted2");
                return true;
            } else {

                Log.v(TAG,"Permission is revoked2");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
                return false;
            }
        }
        else { //permission is automatically granted on sdk<23 upon installation
            Log.v(TAG,"Permission is granted2");
            return true;
        }
    }

    public class HttpServer extends NanoHTTPD {
        public HttpServer() throws IOException {
            super(8080);
        }

        @Override
        public Response serve(IHTTPSession session) {
            try {

                Map<String, String> parms = session.getParms();



                String imgid = parms.get("imgid");
                if (imgid != null && !imgid.equals("")) {
                    long id = Long.parseLong(imgid);
                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

                    //Bitmap thumbnail = getApplicationContext().getContentResolver().loadThumbnail(contentUri, new Size(1000, 1000), null);
                    //ByteArrayOutputStream o = new ByteArrayOutputStream();
                    //thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, o);
                    //return Response.newFixedLengthResponse(Status.OK, "image/jpeg", o.toByteArray());




                    Bitmap bm = BitmapFactory.decodeStream(getApplicationContext().getContentResolver().openInputStream(contentUri));
                    int w = bm.getWidth();
                    int h = bm.getHeight();
                    Bitmap sbm = Bitmap.createScaledBitmap(bm, (w*500)/h, 500, true);
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    sbm.compress(Bitmap.CompressFormat.JPEG, 80, o);
                    byte[] bb = o.toByteArray();
                    ByteArrayInputStream inStream = new ByteArrayInputStream(bb);
                    return newFixedLengthResponse(Response.Status.OK, "image/jpeg", inStream, bb.length);


                    // TODO possibly better resizing: https://stackoverflow.com/questions/4916159/android-get-thumbnail-of-image-on-sd-card-given-uri-of-original-image


//
//
//                    Bitmap smaller_bm = BitmapFactory.decodeFile(src_path, options);
//
//
//
//                    ParcelFileDescriptor pfd = getApplicationContext().getContentResolver().openFileDescriptor(contentUri, "r");
//                    long size = pfd.getStatSize();
//                    pfd.close();
//
//                    InputStream s = getApplicationContext().getContentResolver().openInputStream(contentUri);
//                    return Response.newFixedLengthResponse(Status.OK, "image/jpeg", s, size);
                }


                String deleteimgid = parms.get("deleteimgid");
                if (deleteimgid != null && !deleteimgid.equals("")) {
                    long id = Long.parseLong(deleteimgid);
                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

                    getApplicationContext().getContentResolver().delete(contentUri, null, null);
                    //final ContentValues values = new ContentValues();
                    //values.put("date_expires", (System.currentTimeMillis() + 48 * DateUtils.HOUR_IN_MILLIS) / 1000);
                    //values.put("is_trashed", 1);
                    //getApplicationContext().getContentResolver().update(contentUri, values, null, null);

                    Response r = newFixedLengthResponse(Response.Status.TEMPORARY_REDIRECT, "text/html", "");
                    r.addHeader("Location", "/");
                    return r;
                }

                String msg = "<html><body>v1<br/>";

                msg +=  Environment.getExternalStorageDirectory().getPath() + "<br/>";


                /*
                String path = parms.get("p");
                if (path == null || path.equals("")) {
                    path = Environment.getExternalStorageDirectory().getPath();
                }

                File file = new File(path);
                msg += "<b>" + file.toString() + "</b>\n\n";

                File[] ff = file.listFiles();
                if (ff != null) {
                    for (File f : ff) {
                        msg += "<a href=\"?p=" + f.getPath() + "\">" + f.getName() + "</a>\n";
                    }
                }
                */



                ////////////////////////////////////////////////////////////////////


                String[] projection = new String[]{
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.BUCKET_ID,
                        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Images.Media.DATA,
                        //MediaStore.Images.ImageColumns.VOLUME_NAME,
                        //MediaStore.Images.ImageColumns.DOCUMENT_ID,
                        //MediaStore.Images.ImageColumns.OWNER_PACKAGE_NAME,
                        //MediaStore.Images.ImageColumns.INSTANCE_ID,
                        //MediaStore.Images.ImageColumns.RELATIVE_PATH,
                };

                String CAMERA_IMAGE_BUCKET_NAME = Environment.getExternalStorageDirectory().toString()+ "/DCIM/Camera";

                String CAMERA_IMAGE_BUCKET_ID = String.valueOf(CAMERA_IMAGE_BUCKET_NAME.toLowerCase().hashCode());

//                String selection = MediaStore.Images.Media.BUCKET_ID + " = ?";
//                String[] selectionArgs = { CAMERA_IMAGE_BUCKET_ID };
                String selection = "";
                String[] selectionArgs = { };

                String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";
                //String sortOrder = MediaStore.Images.Media._ID + " ASC";


                Cursor cur = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection, // Which columns to return
                        selection,       // Which rows to return (all rows)
                        selectionArgs,       // Selection arguments (none)
                        sortOrder        // Ordering
                );

                int idIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID);

                int i = 0;
                while(cur.moveToNext()) {
                    long id = cur.getLong(idIdx);
                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);



                    /*
                    Bitmap thumbnail = getApplicationContext().getContentResolver().loadThumbnail(contentUri, new Size(800, 600), null);

                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, o);

                     */

                    msg +=  "ID: id " + id
                            + ", contentUri: " + contentUri
                            + ", bucketId: "+ cur.getString(cur.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID))
                            + ", bucketName:" + cur.getString(cur.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME))
                            + ", DATA:" + cur.getString(cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                            //+ ", DOCUMENT_ID:" + cur.getString(cur.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DOCUMENT_ID))
                            //+ ", OWNER_PACKAGE_NAME:" + cur.getString(cur.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.OWNER_PACKAGE_NAME))
                            //+ ", INSTANCE_ID:" + cur.getString(cur.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.INSTANCE_ID))
                            //+ ", RELATIVE_PATH:" + cur.getString(cur.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.RELATIVE_PATH))
                            + "<br/>\n";
                    msg += "<img src=\"/?imgid=" + id + "\"/>\n";
                    msg += "<a style=\" position:absolute; margin-left: -2em;\" href=\"/?deleteimgid=" + id + "\">Del</a> \n";
                    msg += "<br/>\n";
                    i++;
                    if (i > 100) {
                        break;
                    }
                }


                return newFixedLengthResponse( msg + "</body></html>\n" );

            } catch(Exception e) {
                return newFixedLengthResponse(e.toString());
            }
        }

    }

}
