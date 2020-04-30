package com.acoby.wifiphotos;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;

public class LibjpegTurbo {
    static {
        System.loadLibrary("native-lib");
    }

    public static native Bitmap decompress(ByteBuffer jpegData);

    public static native ByteBuffer compress(Bitmap input);
}
