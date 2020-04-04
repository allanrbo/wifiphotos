FROM ubuntu:18.04
MAINTAINER Allan Boll <allan@acoby.com>

RUN apt-get update && apt-get install -y \
        default-jdk \
        nano git htop unzip wget

RUN wget -O /tmp/sdk.zip https://dl.google.com/android/repository/commandlinetools-linux-6200805_latest.zip
RUN unzip /tmp/sdk.zip -d /usr/lib/android-sdk  &&  rm /tmp/sdk.zip
ENV ANDROID_HOME=/usr/lib/android-sdk
ENV ANDROID_SDK_ROOT=/usr/lib/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/tools/bin
ENV PATH=${PATH}:${ANDROID_HOME}/platform-tools
RUN yes | sdkmanager --sdk_root=${ANDROID_HOME} --licenses
RUN sdkmanager --sdk_root=${ANDROID_HOME} "platform-tools" "platforms;android-29"
