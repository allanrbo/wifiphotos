Downloads and compiles libjpeg-turbo for Android for armeabi-v7a, arm64-v8a, x86, x86_64.

Build the build-container:

    docker build -t libjpegturboandroid .


Run the build container to produce the libjpeg-turbo binaries:

    docker run --name libjpegturboandroid --rm -it -v `pwd`:/libjpegturboandroid -v $HOME:/home/user1 --entrypoint /bin/bash --workdir /libjpegturboandroid  libjpegturboandroid
        # Inside the container, run this
        ./build.sh
