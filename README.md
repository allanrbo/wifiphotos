# WifiPhotos
Manage your phone's photos via a web interface over WiFi.

In the Google Play store: https://play.google.com/store/apps/details?id=com.acoby.wifiphotos

Build in Android Studio: Tools -> SDK Manger -> SDK Tools -> Enable NDK and CMake.

Build in Docker:

    mkdir -p `pwd`/../wifiphotoscache/.gradle

    docker build -t wifiphotos .
    docker run --name wifiphotos --rm -it -v `pwd`:/wifiphotos -v `pwd`/../wifiphotoscache/.gradle:/root/.gradle -v $HOME:/home/user1 --entrypoint /bin/bash --workdir /wifiphotos  wifiphotos  --init-file /home/user1/.myprofile

        ./gradlew build

Publishing:

 - Increment versionCode and versionName in app/build.gradle.
 - Android Studio -> Build -> Generate Signed Bundle / APK -> App Bundle -> Point keystore to .jks file, key alias "key0".
 - Upload .aab file to https://play.google.com/apps/publish/ .
