#!/bin/sh
# Builds lg-remote.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
rm -rf out && mkdir -p out/classes
$BT/aapt2 link -o out/base.apk -I $JAR --manifest AndroidManifest.xml --min-sdk-version 21 --target-sdk-version 34
javac --release 8 -cp $JAR -d out/classes $(find src -name "*.java")
$BT/d8 --min-api 21 --lib $JAR --output out out/classes/app/lgremote/*.class
(cd out && zip -q base.apk classes.dex)
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias key -keyalg RSA -validity 10000 -dname CN=lgremote
$BT/zipalign -f 4 out/base.apk out/aligned.apk
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --out lg-remote.apk out/aligned.apk
echo built $(pwd)/lg-remote.apk
