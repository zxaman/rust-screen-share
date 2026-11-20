"""Make a self-contained offline dashboard for the two preview launchers."""
import base64
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1] / 'apps/desktop'


def uri(name, mime):
    data = (ROOT / 'assets' / name).read_bytes()
    return 'data:' + mime + ';base64,' + base64.b64encode(data).decode()


def bundle():
    css = (ROOT / 'style.css').read_text().replace('assets/font.woff2', uri('font.woff2', 'font/woff2')).replace('assets/topology.svg', uri('topology.svg', 'image/svg+xml'))
    artwork = {name: uri(name + '.svg', 'image/svg+xml') for name in ('desktop', 'phone', 'laptop')}
    js = 'const artwork = ' + json.dumps(artwork) + ';\n' + (ROOT / 'app.js').read_text()
    js = js.replace('assets/icons.svg#', '#').replace('assets/${d.type}.svg', '${artwork[d.type]}')
    icons = (ROOT / 'assets/icons.svg').read_text().replace('<svg ', '<svg aria-hidden="true" width="0" height="0" style="position:absolute" ', 1)
    html = (ROOT / 'index.html').read_text().replace('<body>', '<body>' + icons)
    html = html.replace('<link rel="stylesheet" href="style.css" />', '<style>' + css + '</style>')
    html = html.replace('<script src="app.js"></script>', '<script>' + js + '</script>')
    # No network resources, child frames, external scripts or form submissions.
    html = html.replace('<head>', '''<head><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; base-uri 'none'; form-action 'none'">''')
    for name in ('font-LICENSE', 'lucide-LICENSE'):
        html += '\n<!-- ' + (ROOT / 'assets' / name).read_text().replace('--', '- -') + ' -->'
    return html


if __name__ == '__main__':
    path = Path(sys.argv[1]); path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(bundle(), encoding='utf-8')
