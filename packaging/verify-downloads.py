"""Guard against publishing the old WebView/HTML launcher as the live clients."""
from hashlib import sha256
from pathlib import Path
from struct import unpack_from
from zipfile import ZipFile

files = Path(__file__).resolve().parents[1] / 'downloads'
apk, exe = 'Nodeport-Link-Android.apk', 'Nodeport-Link-Windows-x64.exe'
expected = {apk, exe}
if {p.name for p in files.iterdir() if p.suffix in {'.exe', '.apk'}} != expected:
    raise SystemExit('Unexpected demo or missing native download')
sums = (files / 'LINK-SHA256SUMS.txt').read_text().splitlines()
if sorted(sums) != sorted(f'{sha256((files / name).read_bytes()).hexdigest()}  {name}' for name in expected):
    raise SystemExit('SHA-256 manifest mismatch')
with ZipFile(files / apk) as z:
    dex = z.read('classes.dex')
    if (not dex.startswith(b'dex\n') or b'Lio/nodeport/link/LinkActivity;' not in dex
            or b'NP-HOST/1' not in dex or b'Landroid/webkit/WebView;' in dex
            or any(p.endswith('.html') for p in z.namelist())):
        raise SystemExit('Unexpected Android payload')
pe = (files / exe).read_bytes()
offset, = unpack_from('<I', pe, 0x3c)
if pe[:2] != b'MZ' or pe[offset:offset+6] != b'PE\0\0\x64\x86' or b'NP-HOST/1' not in pe:
    raise SystemExit('Expected native x64 discovery client')
print('Native Link packages and checksums verified')
