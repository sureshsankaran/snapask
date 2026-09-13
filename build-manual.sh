#!/bin/bash
# Manual SnapAsk build: aapt2 + kotlinc + d8 (Gradle daemon is unusable in this env).
set -e
export JAVA_HOME=/home/hatch/.jdk/jdk-17.0.20.1+1
export PATH=$JAVA_HOME/bin:$PATH

APP=/home/hatch/workspace/apps/snapask/app/src/main
OUT=/home/hatch/workspace/apps/snapask/out  # persistent: /tmp is wiped on VM restarts
SDK=/home/hatch/.android-sdk
AAPT2=$SDK/build-tools/34.0.0/aapt2
D8=$SDK/build-tools/34.0.0/d8
ZIPALIGN=$SDK/build-tools/34.0.0/zipalign
APKSIGNER=$SDK/build-tools/34.0.0/apksigner
ANDROID_JAR=$SDK/platforms/android-34/android.jar
# kotlinc lives in ~/.local so it survives VM restarts (/opt is ephemeral)
KOTLINC=$HOME/.local/kotlinc/bin/kotlinc
STDLIB=$HOME/.local/kotlinc/lib/kotlin-stdlib.jar
# Stable debug keystore: generated ONCE outside /tmp/snapbuild so every rebuild
# keeps the same certificate (rm -rf $OUT wipes /tmp/snapbuild each build).
KEYSTORE=/home/hatch/.android/snapask-debug.keystore

rm -rf $OUT
mkdir -p $OUT/compiled-res $OUT/classes $OUT/dex $OUT/gen $OUT/libs $OUT/apk

echo "== extracting androidx classes =="
extract_aar() { # $1 = aar path -> classes.jar + res/ + manifest (for R classes)
  local aar="$1" name
  name=$(basename "$aar" .aar)
  mkdir -p "$OUT/libs/$name"
  unzip -o -q "$aar" classes.jar -d "$OUT/libs/$name" 2>/dev/null || true
  mkdir -p "$OUT/libs/$name/aar"
  unzip -o -q "$aar" "res/*" "AndroidManifest.xml" -d "$OUT/libs/$name/aar" 2>/dev/null || true
}
for aar in \
  /home/hatch/.gradle/caches/modules-2/files-2.1/androidx.core/core/1.13.1/*/core-1.13.1.aar \
  /home/hatch/.gradle/caches/modules-2/files-2.1/androidx.activity/activity/1.9.2/*/activity-1.9.2.aar \
  ; do
  extract_aar "$aar"
done
# transitive deps that core/activity reference at compile time
for art in annotation/annotation arch.core/core-common arch.core/core-runtime \
           lifecycle/lifecycle-runtime lifecycle/lifecycle-common \
           lifecycle/lifecycle-viewmodel lifecycle/lifecycle-viewmodel-savedstate \
           savedstate/savedstate collection/collection customview/customview \
           drawerlayout/drawerlayout interpolator/interpolator loader/loader \
           resourceinspection/resourceinspection \
           versionedparcelable/versionedparcelable concurrent/concurrent-futures \
           tracing/tracing core/core-splashscreen; do
  # AARs -> extract classes.jar
  for aar in $(find /home/hatch/.gradle/caches/modules-2/files-2.1/androidx.$art -name "*.aar" 2>/dev/null | head -2); do
    extract_aar "$aar"
  done
  # plain JARs (java-only artifacts like lifecycle-common, annotation-jvm)
  for jar in $(find /home/hatch/.gradle/caches/modules-2/files-2.1/androidx.$art -name "*.jar" 2>/dev/null | grep -v -e sources -e javadoc | head -4); do
    name=$(basename $jar .jar)
    mkdir -p $OUT/libs/$name
    cp $jar $OUT/libs/$name/classes.jar
  done
done
CP_JARS=$(find $OUT/libs -name classes.jar | tr '\n' ':')
echo "classpath jars: $(echo $CP_JARS | tr ':' '\n' | wc -l)"

echo "== generating R classes for AAR resources =="
for d in "$OUT"/libs/*/; do
  if [ -d "$d/aar/res" ] && [ -f "$d/aar/AndroidManifest.xml" ]; then
    $AAPT2 compile --dir "$d/aar/res" -o "$d/aar-res.zip" 2>/dev/null || continue
    # dummy link just to emit R.java for the AAR's package into $OUT/gen
    $AAPT2 link -o "$d/aar-dummy.apk" --manifest "$d/aar/AndroidManifest.xml" \
      -I $ANDROID_JAR --java "$OUT/gen" "$d/aar-res.zip" >/dev/null 2>&1 || true
    rm -f "$d/aar-dummy.apk"
  fi
done
echo "R.java files: $(find $OUT/gen -name R.java | wc -l)"

echo "== aapt2 compile =="
$AAPT2 compile --dir $APP/res -o $OUT/compiled-res.zip

echo "== prepare manifest =="
sed 's/${applicationId}/com.snapask.app/' $APP/AndroidManifest.xml \
  | sed 's|<manifest |<manifest package="com.snapask.app" |' \
  > $OUT/AndroidManifest.xml

echo "== aapt2 link =="
$AAPT2 link -o $OUT/apk/base.apk \
  -I $ANDROID_JAR \
  --manifest $OUT/AndroidManifest.xml \
  --java $OUT/gen \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  $OUT/compiled-res.zip

echo "== kotlinc (.java on source path for R resolution; classes emitted via javac below) =="
$KOTLINC -jvm-target 17 -no-reflect \
  -cp "$ANDROID_JAR:${CP_JARS}$STDLIB" \
  -d $OUT/classes \
  $(find $APP/java -name "*.kt") $(find $OUT/gen -name "*.java")

echo "== javac (R classes: kotlinc silently skips .java sources) =="
find $OUT/gen -name "*.java" > $OUT/java-sources.txt
if [ -s $OUT/java-sources.txt ]; then
  $JAVA_HOME/bin/javac -source 17 -target 17 -nowarn \
    -cp "$ANDROID_JAR:${CP_JARS}$STDLIB" \
    -d $OUT/classes \
    @$OUT/java-sources.txt
fi
echo "R classes compiled: $(find $OUT/classes -name 'R*.class' | wc -l)"

echo "== d8 =="
$D8 --min-api 26 --lib $ANDROID_JAR \
  --output $OUT/dex \
  $(find $OUT/classes -name "*.class") \
  $(find $OUT/libs -name classes.jar) \
  $STDLIB

echo "== package apk =="
cp $OUT/apk/base.apk $OUT/apk/snapask.apk
(cd $OUT/dex && zip -q -j $OUT/apk/snapask.apk classes*.dex)

echo "== zipalign + sign =="
$ZIPALIGN -f 4 $OUT/apk/snapask.apk $OUT/apk/snapask-aligned.apk
if [ ! -f "$KEYSTORE" ]; then
  echo "ERROR: stable keystore missing at $KEYSTORE" >&2; exit 1
fi
$APKSIGNER sign --ks "$KEYSTORE" --ks-pass pass:android \
  --out $OUT/snapask-debug.apk $OUT/apk/snapask-aligned.apk
$APKSIGNER verify --print-certs $OUT/snapask-debug.apk | head -3
ls -lh $OUT/snapask-debug.apk
echo BUILD_OK
