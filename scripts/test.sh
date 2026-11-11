#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p work/test-classes work/tmp
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/work/tmp"
[[ -s tools/conscrypt-jvm.jar ]] || curl -fLsS https://repo.maven.apache.org/maven2/org/conscrypt/conscrypt-openjdk-uber/2.9.0/conscrypt-openjdk-uber-2.9.0.jar -o tools/conscrypt-jvm.jar
if [[ ! -f work/tls-test.p12 ]]; then
    keytool -genkeypair -keystore work/tls-test.p12 -storetype PKCS12 -storepass test-only-password \
        -alias localhost -dname CN=localhost -ext SAN=dns:localhost -keyalg RSA -keysize 2048 -validity 30
fi
CP="tools/conscrypt-jvm.jar:tools/android.jar:$(find tools/deps -name '*.jar' ! -name '*sources*' ! -name 'conscrypt.jar' -printf '%p:' | sort)"
java -jar tools/ecj.jar -8 -warn:none -cp "$CP" -d work/test-classes \
    src/com/nova/iptv/advanced/*.java tests/NetworkPolicyTest.java tests/AndroidBridgeTest.java tests/ExperimentalTlsTest.java tests/V9TransportTest.java tests/LocalShaperTest.java
java -cp "work/test-classes:$CP" com.nova.iptv.advanced.NetworkPolicyTest "$@"
java -cp "work/test-classes:$CP" com.nova.iptv.advanced.AndroidBridgeTest
java -cp "work/test-classes:$CP" com.nova.iptv.advanced.ExperimentalTlsTest
java -cp "work/test-classes:$CP" com.nova.iptv.advanced.V9TransportTest
java -cp "work/test-classes:$CP" com.nova.iptv.advanced.LocalShaperTest
java -jar tools/apktool.jar d -f -s dist/NovaIPTV-FireTV-v10-Local-Shaper.apk -o work/verified_v9 -p work/framework
python3 tests/verify_apk.py
