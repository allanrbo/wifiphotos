#!/bin/bash

rm -fr /libjpegturboandroid/tmpsrc
cd /libjpegturboandroid/
wget https://github.com/libjpeg-turbo/libjpeg-turbo/archive/2.0.4.tar.gz -O /tmp/libjpeg-turbo.tar.gz
exit_status=$?
if [ $exit_status -ne 0 ]; then
    exit $exit_status
fi
tar -xzf /tmp/libjpeg-turbo.tar.gz
mv /libjpegturboandroid/libjpeg-turbo* /libjpegturboandroid/tmpsrc
exit_status=$?
if [ $exit_status -ne 0 ]; then
    exit $exit_status
fi
rm /tmp/libjpeg-turbo.tar.gz

NDK_PATH=/usr/lib/android-sdk/ndk/21.1.6352462
TOOLCHAIN=clang
ANDROID_VERSION=21

rm -fr /libjpegturboandroid/build
mkdir -p /libjpegturboandroid/out

mkdir -p /libjpegturboandroid/build/x86
cd /libjpegturboandroid/build/x86
cmake -G"Unix Makefiles" \
    -DANDROID_ABI=x86 \
    -DANDROID_PLATFORM=android-${ANDROID_VERSION} \
    -DANDROID_TOOLCHAIN=${TOOLCHAIN} \
    -DCMAKE_TOOLCHAIN_FILE=${NDK_PATH}/build/cmake/android.toolchain.cmake \
    /libjpegturboandroid/tmpsrc/
exit_status=$?
if [ $exit_status -ne 0 ]; then
    exit $exit_status
fi
make
exit_status=$?
if [ $exit_status -ne 0 ]; then
    exit $exit_status
fi
mkdir -p /libjpegturboandroid/out/x86/
cp /libjpegturboandroid/build/x86/libturbojpeg.so /libjpegturboandroid/out/x86/


mkdir -p /libjpegturboandroid/build/x86_64
cd /libjpegturboandroid/build/x86_64
cmake -G"Unix Makefiles" \
    -DANDROID_ABI=x86_64 \
    -DANDROID_PLATFORM=android-${ANDROID_VERSION} \
    -DANDROID_TOOLCHAIN=${TOOLCHAIN} \
    -DCMAKE_TOOLCHAIN_FILE=${NDK_PATH}/build/cmake/android.toolchain.cmake \
    /libjpegturboandroid/tmpsrc/
exit_status=$?
if [ $exit_status -ne 0 ]; then
    exit $exit_status
fi
make
exit_status=$?
if [ $exit_status -ne 0 ]; then
    exit $exit_status
fi
mkdir -p /libjpegturboandroid/out/x86_64/
cp /libjpegturboandroid/build/x86_64/libturbojpeg.so /libjpegturboandroid/out/x86_64/


mkdir -p /libjpegturboandroid/build/armeabi-v7a
cd /libjpegturboandroid/build/armeabi-v7a
cmake -G"Unix Makefiles" \
    -DANDROID_ABI=armeabi-v7a \
    -DANDROID_ARM_MODE=arm \
    -DANDROID_PLATFORM=android-${ANDROID_VERSION} \
    -DANDROID_TOOLCHAIN=${TOOLCHAIN} \
    -DCMAKE_ASM_FLAGS="--target=arm-linux-androideabi${ANDROID_VERSION}" \
    -DCMAKE_TOOLCHAIN_FILE=${NDK_PATH}/build/cmake/android.toolchain.cmake \
    /libjpegturboandroid/tmpsrc/
exit_status=$?
if [ $exit_status -ne 0 ]; then
    exit $exit_status
fi
make
exit_status=$?
if [ $exit_status -ne 0 ]; then
    exit $exit_status
fi
mkdir -p /libjpegturboandroid/out/armeabi-v7a/
cp /libjpegturboandroid/build/armeabi-v7a/libturbojpeg.so /libjpegturboandroid/out/armeabi-v7a/


mkdir -p /libjpegturboandroid/build/arm64-v8a
cd /libjpegturboandroid/build/arm64-v8a
cmake -G"Unix Makefiles" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_ARM_MODE=arm \
    -DANDROID_PLATFORM=android-${ANDROID_VERSION} \
    -DANDROID_TOOLCHAIN=${TOOLCHAIN} \
    -DCMAKE_ASM_FLAGS="--target=aarch64-linux-android${ANDROID_VERSION}" \
    -DCMAKE_TOOLCHAIN_FILE=${NDK_PATH}/build/cmake/android.toolchain.cmake \
    /libjpegturboandroid/tmpsrc/
exit_status=$?
if [ $exit_status -ne 0 ]; then
    exit $exit_status
fi
make
exit_status=$?
if [ $exit_status -ne 0 ]; then
    exit $exit_status
fi
mkdir -p /libjpegturboandroid/out/arm64-v8a/
cp /libjpegturboandroid/build/arm64-v8a/libturbojpeg.so /libjpegturboandroid/out/arm64-v8a/

