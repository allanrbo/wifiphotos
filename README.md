# WifiPhotos
Manage your phone's photos via a web interface over WiFi.

    mkdir -p `pwd`/../wifiphotoscache/.gradle

    docker build -t wifiphotos .
    docker run --name wifiphotos --rm -it -v `pwd`:/wifiphotos -v `pwd`/../wifiphotoscache/.gradle:/root/.gradle -v $HOME:/home/user1 --entrypoint /bin/bash --workdir /wifiphotos  wifiphotos  --init-file /home/user1/.myprofile

        ./gradlew build
