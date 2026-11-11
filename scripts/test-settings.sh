#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
APK="${1:-$ROOT/dist/NovaIPTV-FireTV-v10-Local-Shaper.apk}"
[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }
APK="$(realpath "$APK")"
mkdir -p tools/ui-deps work/ui-test-classes work/tmp work/test-home
export JAVA_TOOL_OPTIONS="-Xmx430m -Djava.io.tmpdir=$ROOT/work/tmp -Duser.home=$ROOT/work/test-home"
fetch() { [[ -s "$2" ]] || curl -fLsS "$1" -o "$2"; }
fetch https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz tools/maven.tar.gz
[[ -d tools/apache-maven-3.9.9 ]] || tar -xzf tools/maven.tar.gz -C tools
fetch https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip tools/dex-tools.zip
[[ -d tools/dex-tools-v2.4 ]] || unzip -q tools/dex-tools.zip -d tools
fetch https://repo.maven.apache.org/maven2/org/robolectric/android-all/9-robolectric-4913185-2/android-all-9-robolectric-4913185-2.jar tools/android-runtime.jar
fetch https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.37.0/ecj-3.37.0.jar tools/ecj.jar
tools/apache-maven-3.9.9/bin/mvn -q -f tests/settings-runtime-pom.xml \
    -Dmaven.repo.local="$ROOT/tools/m2" dependency:copy-dependencies -DoutputDirectory="$ROOT/tools/ui-deps"
for archive in tools/ui-deps/*.aar; do unzip -p "$archive" classes.jar > "${archive%.aar}.jar"; done
bash tools/dex-tools-v2.4/d2j-dex2jar.sh -f -o work/settings-under-test.jar "$APK"
CP="tools/android-runtime.jar:$(find tools/ui-deps -name '*.jar' -printf '%p:'):work/settings-under-test.jar"
java -jar tools/ecj.jar -8 -warn:none -cp "$CP" -d work/ui-test-classes tests/SettingsActivityRuntimeTest.java
for sdk in ${NOVA_TEST_SDKS:-22 23 28 33}; do
    echo "Testing actual Settings screen and player navigation on Android API $sdk"
    java -Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2 \
        -Drobolectric.enabledSdks="$sdk" -Dnova.settings.apk="$APK" \
        -cp "work/ui-test-classes:$CP" org.junit.runner.JUnitCore SettingsActivityRuntimeTest
done
