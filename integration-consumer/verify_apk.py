"""Verify the actual composite-built APK, including manifest merge and natives."""
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

apk = Path(sys.argv[1])
result = subprocess.run(['apkanalyzer', 'manifest', 'print', str(apk)],
                        check=True, capture_output=True, text=True)
manifest = ET.fromstring(result.stdout)
ns = '{http://schemas.android.com/apk/res/android}'
assert manifest.attrib['package'] == 'dev.abdullah.openlock'
app = manifest.find('application')
assert app is not None
assert app.get(ns + 'name') != 'moe.shizuku.manager.ShizukuApplication'
assert app.get(ns + 'extractNativeLibs') == 'true'
components = {node.get(ns+'name'): node for node in app
              if node.tag in {'activity', 'service', 'receiver', 'provider'}}
home = components['moe.shizuku.manager.MainActivity']
assert home.get(ns+'exported') == 'false'
assert home.find('intent-filter') is None
for name, node in components.items():
    if name.startswith('moe.shizuku.manager.'):
        assert node.get(ns+'process') == ':shizuku', name
provider = components['moe.shizuku.manager.ShizukuManagerProvider']
assert provider.get(ns+'authorities') == 'dev.abdullah.openlock.shizuku'
init = components['moe.shizuku.manager.integration.ShizukuInitProvider']
assert init.get(ns+'exported') == 'false'
assert init.get(ns+'initOrder') == '100'
declared = [node.get(ns+'name', '') for node in manifest
            if node.tag in {'permission', 'permission-group', 'uses-permission'}]
assert not any(name.startswith('moe.shizuku.manager.permission') for name in declared)
assert 'dev.abdullah.openlock.shizuku.permission.MANAGER' in declared
assert not manifest.findall('.//category[@'+ns+'name="android.intent.category.LAUNCHER"]')
with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    for library in ['libshizuku.so', 'libadb.so', 'librish.so']:
        assert 'lib/arm64-v8a/' + library in names, library
    starter = archive.read('lib/arm64-v8a/libshizuku.so')
    assert b'dev.abdullah.openlock' in starter
    assert b'openlock_shizuku_server' in starter
    dex = b''.join(archive.read(name) for name in names
                   if name.startswith('classes') and name.endswith('.dex'))
    assert b'openlock-shizuku.json' in dex
    assert 'assets/rish_shizuku.dex' in names
print('Passed APK checks: host identity, original UI, isolated process, private entry, permissions, native executables and rish dex.')
