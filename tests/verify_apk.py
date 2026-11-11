#!/usr/bin/env python3
"""Check patch scope, UI preservation and new dex linkage against original classes."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
a = ROOT / 'work/original'
b = ROOT / 'work/patched'
assert a.is_dir() and b.is_dir(), 'Decode original and build modified APK first'
expected = {
    'apktool.yml',
    'res/layout/activity_settings.xml',
    'res/values/public.xml',
    'smali/androidx/media3/datasource/DefaultDataSource$Factory.smali',
    'smali_classes2/com/nova/iptv/NovaApp.smali',
    'smali_classes2/com/nova/iptv/ui/SettingsActivity.smali',
    'smali_classes2/com/nova/iptv/data/PlaylistRepository.smali',
    'smali_classes2/com/nova/iptv/data/XtreamApi.smali',
    'smali_classes2/com/nova/iptv/util/NovaDrm.smali',
}
changed = set()
count = 0
for path in a.rglob('*'):
    if not path.is_file() or path.parts[len(a.parts)] in {'build', 'dist'}:
        continue
    rel = path.relative_to(a)
    other = b / rel
    assert other.is_file(), f'Original file removed: {rel}'
    count += 1
    if path.read_bytes() != other.read_bytes():
        changed.add(str(rel))
assert changed == expected, f'Unexpected change scope: {changed ^ expected}'
original_layout = (a / 'res/layout/activity_settings.xml').read_text()
patched_layout = (b / 'res/layout/activity_settings.xml').read_text()
card = (ROOT / 'scripts/advanced_settings.xml').read_text()
assert patched_layout.replace(card, '') == original_layout, 'Existing Settings UI changed'
ns = '{http://schemas.android.com/apk/res/android}'
layout = ET.fromstring(patched_layout)
tags = {element.get(ns + 'tag'): element.tag for element in layout.iter() if element.get(ns + 'tag')}
assert tags == {
    'advanced_ua_switch': 'Switch', 'advanced_ua_text': 'EditText',
    'advanced_referer_switch': 'Switch', 'advanced_referer_text': 'EditText', 'advanced_dns': 'Spinner',
    'advanced_fragmentation': 'com.google.android.material.materialswitch.MaterialSwitch',
    'advanced_omit_sni': 'com.google.android.material.materialswitch.MaterialSwitch',
    'advanced_continuous': 'com.google.android.material.materialswitch.MaterialSwitch',
    'advanced_http_padding': 'com.google.android.material.materialswitch.MaterialSwitch',
    'advanced_host_case': 'com.google.android.material.materialswitch.MaterialSwitch',
    'advanced_tls_padding_unavailable': 'com.google.android.material.materialswitch.MaterialSwitch'
}
# Every new MaterialSwitch needs M3 color tokens and safe internal text defaults.
app_ns = '{http://schemas.android.com/apk/res-auto}'
for element in layout.iter('com.google.android.material.materialswitch.MaterialSwitch'):
    assert element.get(ns + 'theme') == '@style/Theme.Material3.Dark'
    assert element.get(app_ns + 'showText') == 'false'
    assert element.get(ns + 'textOn') == '@string/abc_capital_on'
    assert element.get(ns + 'textOff') == '@string/abc_capital_off'
unavailable = next(e for e in layout.iter() if e.get(ns + 'tag') == 'advanced_tls_padding_unavailable')
assert unavailable.get(ns + 'enabled') == 'false' and unavailable.get(ns + 'checked') == 'false'
assert unavailable.get(ns + 'saveEnabled') == 'false'
assert 'RFC 8446' in patched_layout and 'X-Padding' in patched_layout
for abi in ['armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64']:
    assert (b / 'lib' / abi / 'libconscrypt_jni.so').is_file(), f'Missing TLS engine for {abi}'
assert 'versionCode: 11' in (b / 'apktool.yml').read_text()
assert 'versionName: 10.0' in (b / 'apktool.yml').read_text()
assert (b / 'unknown/org/conscrypt/conscrypt.properties').is_file()
assert (b / 'assets/third_party/conscrypt/conscrypt_LICENSE.txt').is_file()
assert (a / 'AndroidManifest.xml').read_bytes() == (b / 'AndroidManifest.xml').read_bytes(), 'Manifest changed'
# Index all original and added types and methods. Verify calls from new classes
# to embedded libraries resolve without replacing their versions.
classes = {}
for folder in b.glob('smali*'):
    for path in folder.rglob('*.smali'):
        text = path.read_text()
        name = re.search(r'^\.class (?:.* )?(L[^;]+;)$', text, re.M).group(1)
        assert name not in classes, f'Duplicate dex type: {name}'
        parent = re.search(r'^\.super (L[^;]+;)$', text, re.M)
        interfaces = re.findall(r'^\.implements (L[^;]+;)$', text, re.M)
        methods = set(re.findall(r'^\.method .*? (\S+\([^\n]+)$', text, re.M))
        classes[name] = (methods, ([parent.group(1)] if parent else []) + interfaces)

def resolves(owner, signature, visited=None):
    visited = set() if visited is None else visited
    if owner in visited:
        return False
    visited.add(owner)
    if owner.startswith(('Ljava/', 'Ljavax/', 'Landroid/', 'Ldalvik/', 'Llibcore/', 'Lcom/android/org/conscrypt/')):
        return True  # Android platform APIs were separately checked by the compiler.
    if owner not in classes:
        return False
    methods, parents = classes[owner]
    return signature in methods or any(resolves(p, signature, visited) for p in parents)

calls = 0
for path in (b / 'smali_classes3').rglob('*.smali'):
    text = path.read_text()
    for owner, signature in re.findall(r'invoke-[^\n]*?, (L[^;]+;)->(\S+)', text):
        assert resolves(owner, signature), f'Unresolved added call in {path.name}: {owner}->{signature}'
        calls += 1
print(f'PASS: {count} original files retained; exactly nine intended files changed (including release metadata and pinned resource IDs)')
print('PASS: existing Settings XML unchanged after removing the new network card')
print(f'PASS: {len(classes)} unique dex classes; {calls} added method references resolved')

# Check the final signed artifact, not just the decoded build inputs.
final = ROOT / 'work/verified_v9'
def xml_signature(element):
    return (element.tag, {key: value.replace('@+id/', '@id/') for key, value in element.attrib.items()},
            (element.text or '').strip(), [xml_signature(child) for child in element])
for name in ['AndroidManifest.xml', 'res/layout/activity_settings.xml']:
    assert xml_signature(ET.parse(b / name).getroot()) == xml_signature(ET.parse(final / name).getroot()), name
old_public = ET.parse(a / 'res/values/public.xml').getroot()
new_public = ET.parse(final / 'res/values/public.xml').getroot()
resource_ids = {(element.get('type'), element.get('name')): element.get('id') for element in new_public}
for element in old_public:
    assert resource_ids[(element.get('type'), element.get('name'))] == element.get('id'), element.attrib
for name, resource_id in [('cbPreferFragmentation', '0x7f0a0291'), ('cbPreferSniSpoofing', '0x7f0a0292'),
                          ('cbContinuousFragmentation', '0x7f0a0293'), ('cbHttpPadding', '0x7f0a0294'),
                          ('cbTlsPadding', '0x7f0a0295'), ('cbHttpHostCase', '0x7f0a0296')]:
    assert resource_ids[('id', name)] == resource_id, name
release = (final / 'apktool.yml').read_text()
assert 'versionCode: 11' in release and 'versionName: 10.0' in release
print('PASS: signed v10 manifest/layout, original resource IDs and stable v8/v9 switch IDs verified')
