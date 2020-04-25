#include "turbojpeg.h"
#include <android/bitmap.h>
#include <jni.h>
#include <string>
#include <android/log.h>

#define TAG "WifiPhotos"

using namespace std;

static void throwRuntimeException(JNIEnv *env, const char *message) {
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(exClass, message);
}

static jobject createBitmap(JNIEnv *env, int width, int height) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (env->ExceptionCheck()) {
        return NULL;
    }

    const char* sig = "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;";
    jmethodID createBitmapMethodID = env->GetStaticMethodID(bitmapClass, "createBitmap", sig);
    if (env->ExceptionCheck()) {
        return NULL;
    }

    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (env->ExceptionCheck()) {
        return NULL;
    }

    sig = "Landroid/graphics/Bitmap$Config;";
    jfieldID argb8888FieldId = env->GetStaticFieldID(configClass, "ARGB_8888", sig);
    if (env->ExceptionCheck()) {
        return NULL;
    }

    jobject argb8888 = env->GetStaticObjectField(configClass, argb8888FieldId);
    if (env->ExceptionCheck()) {
        return NULL;
    }

    jobject bitmapObj = env->CallStaticObjectMethod(bitmapClass, createBitmapMethodID, width, height, argb8888);
    if (env->ExceptionCheck()) {
        return NULL;
    }

    return bitmapObj;
}


static jobject createByteBuffer(JNIEnv *env, long capacity) {
    jclass byteBufferClass = env->FindClass("java/nio/ByteBuffer");
    if (env->ExceptionCheck()) {
        return NULL;
    }

    const char* sig = "(I)Ljava/nio/ByteBuffer;";
    jmethodID allocateDirectMethodID = env->GetStaticMethodID(byteBufferClass, "allocateDirect", sig);
    if (env->ExceptionCheck()) {
        return NULL;
    }

    jobject byteBufferObj = env->CallStaticObjectMethod(byteBufferClass, allocateDirectMethodID, capacity);
    if (env->ExceptionCheck()) {
        return NULL;
    }

    return byteBufferObj;
}


extern "C" JNIEXPORT JNICALL
jobject Java_com_acoby_wifiphotos_LibjpegTurbo_decompress(JNIEnv *env, jclass c, jobject jpegData) {
    unsigned char *jpegBuf = (unsigned char *) env->GetDirectBufferAddress(jpegData);
    long jpegSize = env->GetDirectBufferCapacity(jpegData);

    tjhandle tjInstance = NULL;
    if ((tjInstance = tjInitDecompress()) == NULL) {
        throwRuntimeException(env, "tjInitDecompress failed");
        return NULL;
    }

    int width, height;
    int inSubsamp, inColorspace;
    if (tjDecompressHeader3(tjInstance, jpegBuf, jpegSize, &width, &height, &inSubsamp, &inColorspace) < 0) {
        throwRuntimeException(env, "tjDecompressHeader3 failed");
        return NULL;
    }

    jobject bitmap = createBitmap(env, width, height);
    if (env->ExceptionCheck()) {
        return NULL;
    }

    void *bitmapPixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &bitmapPixels) != 0) {
        throwRuntimeException(env, "AndroidBitmap_lockPixels failed");
    }

    const int pixelFormat = TJPF_RGBA;
    const int flags = 0;
    if (tjDecompress2(tjInstance, jpegBuf, jpegSize, (unsigned char *) bitmapPixels, width, 0, height, pixelFormat, flags) < 0) {
        AndroidBitmap_unlockPixels(env, bitmap);
        throwRuntimeException(env, "tjDecompress2 failed");
        return NULL;
    }

    tjDestroy(tjInstance);

    AndroidBitmap_unlockPixels(env, bitmap);

    return bitmap;
}


extern "C" JNIEXPORT JNICALL
jobject Java_com_acoby_wifiphotos_LibjpegTurbo_compress(JNIEnv *env, jclass c, jobject input, jint width, jint height) {
    tjhandle tjInstance = NULL;
    if ((tjInstance = tjInitCompress()) == NULL) {
        throwRuntimeException(env, "tjInitCompress failed");
        return NULL;
    }

    void *bitmapPixels = NULL;
    if (AndroidBitmap_lockPixels(env, input, &bitmapPixels) != 0) {
        throwRuntimeException(env, "AndroidBitmap_lockPixels failed");
    }

    unsigned long jpegSize;
    unsigned char *jpegBuf = NULL;
    if (tjCompress2(tjInstance, (unsigned char *) bitmapPixels, width, 0, height, TJPF_RGBA, &jpegBuf, &jpegSize, TJSAMP_444, 80, 0) < 0) {
        AndroidBitmap_unlockPixels(env, input);
        throwRuntimeException(env, "tjCompress2 failed");
        return NULL;
    }

    tjDestroy(tjInstance);

    AndroidBitmap_unlockPixels(env, input);

    jobject byteBufferObj = createByteBuffer(env, jpegSize);
    if (env->ExceptionCheck()) {
        return NULL;
    }

    unsigned char *byteBuffer;
    byteBuffer = (unsigned char *) env->GetDirectBufferAddress(byteBufferObj);
    memcpy(byteBuffer, jpegBuf, jpegSize);
    tjFree(jpegBuf);

    return byteBufferObj;
}
