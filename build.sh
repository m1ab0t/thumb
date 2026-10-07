#!/bin/sh
# Builds lg-remote.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Optional: VERSION=1.4 VERSION_CODE=5 override the manifest.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
JDK=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
rm -rf out && mkdir -p out/classes
$BT/aapt2 link -o out/base.apk -I $JAR --manifest AndroidManifest.xml --min-sdk-version 21 --target-sdk-version 34 \
  --replace-version ${VERSION:+--version-name $VERSION} ${VERSION_CODE:+--version-code $VERSION_CODE}
$JDK/bin/javac --release 8 -cp $JAR -d out/classes $(find src -name "*.java")
$BT/d8 --min-api 21 --lib $JAR --output out out/classes/app/lgremote/*.class
(cd out && zip -q base.apk classes.dex)
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias key -keyalg RSA -validity 10000 -dname CN=lgremote
$BT/zipalign -f 4 out/base.apk out/aligned.apk
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --out lg-remote.apk out/aligned.apk
echo built $(pwd)/lg-remote.apk
