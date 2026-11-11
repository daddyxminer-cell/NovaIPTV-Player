#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Tools and dependency jars live only inside the workspace, never in system directories.
INPUT="${1:?Usage: bash scripts/build.sh /absolute/path/to/original.apk}"
mkdir -p tools/deps work/classes work/dex work/tmp dist/signed
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/work/tmp"
fetch() { [[ -s "$2" ]] || curl -fLsS "$1" -o "$2"; }
fetch https://github.com/iBotPeaches/Apktool/releases/download/v2.12.1/apktool_2.12.1.jar tools/apktool.jar
fetch https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.37.0/ecj-3.37.0.jar tools/ecj.jar
fetch https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar tools/android.jar
fetch https://storage.googleapis.com/r8-releases/raw/8.3.37/r8.jar tools/r8.jar
fetch https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar tools/signer.jar
MAVEN=https://repo.maven.apache.org/maven2
fetch "$MAVEN/com/squareup/okhttp3/okhttp/4.11.0/okhttp-4.11.0.jar" tools/deps/okhttp.jar
fetch "$MAVEN/com/squareup/okhttp3/okhttp-dnsoverhttps/4.11.0/okhttp-dnsoverhttps-4.11.0.jar" tools/deps/doh.jar
fetch "$MAVEN/com/squareup/okio/okio-jvm/3.2.0/okio-jvm-3.2.0.jar" tools/deps/okio.jar
fetch "$MAVEN/org/jetbrains/kotlin/kotlin-stdlib/1.8.21/kotlin-stdlib-1.8.21.jar" tools/deps/kotlin.jar
fetch "$MAVEN/com/google/guava/guava/32.1.3-android/guava-32.1.3-android.jar" tools/deps/guava.jar
fetch "$MAVEN/org/conscrypt/conscrypt-android/2.9.0/conscrypt-android-2.9.0.aar" tools/deps/conscrypt.aar
unzip -p tools/deps/conscrypt.aar classes.jar > tools/deps/conscrypt.jar
for lib in media3-common media3-datasource media3-datasource-okhttp; do
    fetch "https://dl.google.com/android/maven2/androidx/media3/$lib/1.3.1/$lib-1.3.1.aar" "tools/deps/$lib.aar"
    unzip -p "tools/deps/$lib.aar" classes.jar > "tools/deps/$lib.jar"
done
CP="tools/android.jar:$(find tools/deps -name '*.jar' ! -name '*sources*' -printf '%p:' | sort)"
java -jar tools/ecj.jar -8 -warn:none -cp "$CP" -d work/classes src/com/nova/iptv/advanced/*.java
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
with ZipFile('work/advanced.jar', 'w', ZIP_DEFLATED) as out:
    for path in sorted(Path('work/classes').rglob('*.class')):
        out.write(path, path.relative_to('work/classes'))
PY
# Only new classes are dexed: all existing Media3, OkHttp, Okio and Kotlin stay untouched.
java -cp tools/r8.jar com.android.tools.r8.D8 --min-api 21 --lib tools/android.jar \
    --classpath tools/deps/okhttp.jar --classpath tools/deps/okio.jar \
    --classpath tools/deps/kotlin.jar --classpath tools/deps/media3-common.jar \
    --classpath tools/deps/media3-datasource.jar --classpath tools/deps/guava.jar \
    --output work/dex work/advanced.jar tools/deps/doh.jar tools/deps/media3-datasource-okhttp.jar tools/deps/conscrypt.jar
java -jar tools/apktool.jar d -f "$INPUT" -o work/original -p work/framework
python3 - <<'PY'
from pathlib import Path
import shutil
source = Path('work/original').resolve()
target = Path('work/patched').resolve()
assert source.parent == target.parent == Path('work').resolve()
if target.exists():
    shutil.rmtree(target)
shutil.copytree(source, target)
PY
python3 scripts/patch_apk.py work/patched
python3 - "$INPUT" <<'PY'
from zipfile import ZipFile, ZIP_DEFLATED
import sys
with ZipFile(sys.argv[1]) as original, ZipFile('work/additions.apk', 'w', ZIP_DEFLATED) as out:
    out.writestr('AndroidManifest.xml', original.read('AndroidManifest.xml'))
    out.writestr('resources.arsc', original.read('resources.arsc'))
    out.write('work/dex/classes.dex', 'classes.dex')
PY
java -jar tools/apktool.jar d -f -r work/additions.apk -o work/additions_decoded -p work/framework
cp -a work/additions_decoded/smali work/patched/smali_classes3
python3 - <<'PY_NATIVE'
from pathlib import Path
from zipfile import ZipFile
import shutil
with ZipFile('tools/deps/conscrypt.aar') as archive:
    for entry in archive.namelist():
        if entry.startswith('jni/') and entry.endswith('.so'):
            target = Path('work/patched/lib') / entry.removeprefix('jni/')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
# Package provider version data and licenses alongside its native engines.
with ZipFile('tools/deps/conscrypt.jar') as jar:
    target = Path('work/patched/unknown/org/conscrypt/conscrypt.properties')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(jar.read('org/conscrypt/conscrypt.properties'))
license_dir = Path('work/patched/assets/third_party/conscrypt')
license_dir.mkdir(parents=True, exist_ok=True)
for source in Path('scripts/licenses').iterdir():
    shutil.copy2(source, license_dir / source.name)
PY_NATIVE
java -jar tools/apktool.jar b work/patched -p work/framework -o dist/NovaIPTV-FireTV-v10-local-shaper-unsigned.apk
# Keep a stable local signing key for repeat builds; never commit or publish it.
if [[ ! -f work/advanced-signing.jks ]]; then
    keytool -genkeypair -keystore work/advanced-signing.jks -storepass nova-local-build \
        -keypass nova-local-build -alias nova-advanced -dname 'CN=Nova IPTV Local Build' \
        -keyalg RSA -keysize 2048 -validity 10000
    chmod 600 work/advanced-signing.jks
fi
rm -f dist/signed/NovaIPTV-FireTV-v10-local-shaper-aligned-signed.apk
java -jar tools/signer.jar --apks dist/NovaIPTV-FireTV-v10-local-shaper-unsigned.apk \
    --ks work/advanced-signing.jks --ksAlias nova-advanced \
    --ksPass nova-local-build --ksKeyPass nova-local-build --out dist/signed
cp dist/signed/NovaIPTV-FireTV-v10-local-shaper-aligned-signed.apk dist/NovaIPTV-FireTV-v10-Local-Shaper.apk
sha256sum dist/NovaIPTV-FireTV-v10-Local-Shaper.apk
