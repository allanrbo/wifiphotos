package com.acoby.wifiphotos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

// https://stackoverflow.com/a/6603018/40645
public class ByteBufferBackedInputStream extends InputStream {
    ByteBuffer buf;

    public ByteBufferBackedInputStream(ByteBuffer buf) {
        buf.rewind();
        this.buf = buf;
    }

    public int read() throws IOException {
        if (!buf.hasRemaining()) {
            return -1;
        }
        return buf.get() & 0xFF;
    }

    public int read(byte[] bytes, int off, int len)
            throws IOException {
        if (!buf.hasRemaining()) {
            return -1;
        }

        len = Math.min(len, buf.remaining());
        buf.get(bytes, off, len);
        return len;
    }
}