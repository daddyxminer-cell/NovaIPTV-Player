#!/usr/bin/env python3
"""Apply narrowly scoped patches to the exact supplied v7 APK; fail on drift."""
from pathlib import Path
import sys
import re

ROOT = Path(__file__).resolve().parents[1]
TARGET = Path(sys.argv[1]).resolve()
if not TARGET.is_relative_to(ROOT / 'work'):
    raise SystemExit('Decoded APK must live under the workspace work directory')


def replace(path, old, new):
    text = path.read_text()
    if text.count(old) != 1:
        raise SystemExit(f'Expected exactly one patch target in {path}: {old[:100]!r}')
    path.write_text(text.replace(old, new))


app = TARGET / 'smali_classes2/com/nova/iptv'
replace(app / 'NovaApp.smali',
    '    invoke-super {p0}, Landroid/app/Application;->onCreate()V',
    '    invoke-super {p0}, Landroid/app/Application;->onCreate()V\n\n'
    '    invoke-static {p0}, Lcom/nova/iptv/advanced/AdvancedNetwork;->init(Landroid/content/Context;)V')
settings = app / 'ui/SettingsActivity.smali'
replace(settings,
    '    invoke-virtual {p0, p1}, Lcom/nova/iptv/ui/SettingsActivity;->setContentView(I)V',
    '    invoke-virtual {p0, p1}, Lcom/nova/iptv/ui/SettingsActivity;->setContentView(I)V\n\n'
    '    invoke-static {p0}, Lcom/nova/iptv/advanced/AdvancedSettings;->bind(Landroid/app/Activity;)V')
# The original Save callback has three locals; v0 is free before original code runs.
marker = '.method private static final onCreate$lambda$13('
text = settings.read_text()
start = text.index(marker)
pos = text.index('    .locals 3', start) + len('    .locals 3')
text = text[:pos] + '''

    invoke-static {p0}, Lcom/nova/iptv/advanced/AdvancedSettings;->save(Landroid/app/Activity;)Z
    move-result v0
    if-nez v0, :advanced_valid
    return-void
    :advanced_valid
''' + text[pos:]
settings.write_text(text)
for name in ['PlaylistRepository', 'XtreamApi']:
    path = app / f'data/{name}.smali'
    old = '    invoke-virtual {p1}, Lokhttp3/OkHttpClient$Builder;->build()Lokhttp3/OkHttpClient;'
    replace(path, old,
        '    invoke-static {p1}, Lcom/nova/iptv/advanced/AdvancedNetwork;->configure(Lokhttp3/OkHttpClient$Builder;)Lokhttp3/OkHttpClient$Builder;\n\n'
        '    move-result-object p1\n\n' + old)
# Select immutable TLS variants before each API/playlist Call is created.
for name, expected_calls in [('PlaylistRepository', 2), ('XtreamApi', 1)]:
    path = app / f'data/{name}.smali'
    text = path.read_text()
    pattern = r'invoke-virtual (\{[^}]+\}), Lokhttp3/OkHttpClient;->newCall\(Lokhttp3/Request;\)Lokhttp3/Call;'
    text, count = re.subn(pattern,
        r'invoke-static \1, Lcom/nova/iptv/advanced/AdvancedNetwork;->newCall(Lokhttp3/OkHttpClient;Lokhttp3/Request;)Lokhttp3/Call;', text)
    if count != expected_calls:
        raise SystemExit(f'Unexpected request call sites in {name}: {count}')
    path.write_text(text)

# ExoPlayer defaults and NovaDrm both use this constructor for media requests.
replace(TARGET / 'smali/androidx/media3/datasource/DefaultDataSource$Factory.smali',
    '    new-instance v0, Landroidx/media3/datasource/DefaultHttpDataSource$Factory;\n\n'
    '    invoke-direct {v0}, Landroidx/media3/datasource/DefaultHttpDataSource$Factory;-><init>()V',
    '    invoke-static {}, Lcom/nova/iptv/advanced/AdvancedNetwork;->httpFactory()Landroidx/media3/datasource/HttpDataSource$Factory;\n\n'
    '    move-result-object v0')
# Widevine license HTTP requests must follow the same app DNS and header policy.
replace(app / 'util/NovaDrm.smali',
    '    new-instance v2, Landroidx/media3/datasource/DefaultHttpDataSource$Factory;\n\n'
    '    invoke-direct {v2}, Landroidx/media3/datasource/DefaultHttpDataSource$Factory;-><init>()V',
    '    invoke-static {}, Lcom/nova/iptv/advanced/AdvancedNetwork;->httpFactory()Landroidx/media3/datasource/HttpDataSource$Factory;\n\n'
    '    move-result-object v2')
layout = TARGET / 'res/layout/activity_settings.xml'
text = layout.read_text()
line = next(line for line in text.splitlines(True) if 'android:id="@id/btnSave"' in line)
replace(layout, line, (ROOT / 'scripts/advanced_settings.xml').read_text() + line)
# Pin v8 switch IDs before adding new names: aapt otherwise allocates them alphabetically.
public_entries = ''.join(
    f'    <public type="id" name="{name}" id="{resource_id}" />\n'
    for name, resource_id in [
        ('cbPreferFragmentation', '0x7f0a0291'),
        ('cbPreferSniSpoofing', '0x7f0a0292'),
        ('cbContinuousFragmentation', '0x7f0a0293'),
        ('cbHttpPadding', '0x7f0a0294'),
        ('cbTlsPadding', '0x7f0a0295'),
        ('cbHttpHostCase', '0x7f0a0296'),
    ])
replace(TARGET / 'res/values/public.xml', '</resources>', public_entries + '</resources>')
# Increment only release metadata so the prior signed customization updates in place.
replace(TARGET / 'apktool.yml', '  versionCode: 7\n  versionName: 7.0',
        '  versionCode: 11\n  versionName: 10.0')
print('Applied network/UI hooks plus v10 release metadata; existing layout preserved.')
