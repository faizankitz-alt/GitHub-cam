"""Runs the camera page with the real AI files and a fake camera showing a photo."""
import base64, pathlib, sys, urllib.request
from playwright.sync_api import sync_playwright

ASSETS = pathlib.Path('app/src/main/assets')
TYPES = {'.mjs': 'text/javascript', '.cjs': 'text/javascript', '.js': 'text/javascript', '.wasm': 'application/wasm'}
IMAGES = {n: 'data:image/jpeg;base64,' + base64.b64encode(urllib.request.urlopen(
    f'https://storage.googleapis.com/mediapipe-assets/{n}.jpg', timeout=60).read()).decode()
    for n in ('portrait', 'cats_and_dogs')}
CAMERA = """navigator.mediaDevices.getUserMedia=async()=>{const i=new Image();i.src=window.SRC;await i.decode();
const c=document.createElement('canvas');c.width=i.width&~1;c.height=i.height&~1;const x=c.getContext('2d');
const d=()=>x.drawImage(i,0,0,c.width,c.height);d();setInterval(d,50);return c.captureStream(20)};"""
results, failed, errors = [], [], []

def check(name, ok, detail=''):
    results.append(f'{name}={"PASS" if ok else "FAIL"} {detail}')
    if not ok:
        failed.append(name)

def route(r):
    path = r.request.url.split('kezmocam.invalid/', 1)[1]
    f = ASSETS / (path or 'index.html')
    if f.is_file():
        r.fulfill(body=f.read_bytes(), content_type=TYPES.get(f.suffix, 'text/html' if f.suffix == '.html' else 'application/octet-stream'))
    else:
        r.fulfill(status=404, body='')

with sync_playwright() as p:
    browser = p.chromium.launch(args=['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'])
    for image, setup, kind in (('portrait', "S.portrait.on=true;S.portrait.mode=0", 'person'),
                               ('cats_and_dogs', "S.portrait.on=true;S.portrait.mode=1;aim={x:.3,y:.6}", 'object'),
                               ('portrait', "S.portrait.on=false;S.lens='Glasses'", 'face')):
        page = browser.new_page(viewport={'width': 390, 'height': 800})
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.on('console', lambda m: m.text.startswith('AI') and errors.append(m.text))
        page.add_init_script(f'window.SRC="{IMAGES[image]}";' + CAMERA)
        page.route('https://kezmocam.invalid/**', route)
        page.goto('https://kezmocam.invalid/')
        try:
            page.wait_for_function('stream && video.videoWidth > 0', timeout=30000)
            page.evaluate(setup)
            if kind == 'face':
                page.wait_for_function('AI.faces.length > 0', timeout=90000)
                f = page.evaluate('({top:AI.faces[0][10].y, chin:AI.faces[0][152].y, mode:AI.mode})')
                check('face', f['top'] < f['chin'] and f['mode'] == 'worker', str(f))
            else:
                page.wait_for_function('AI.mask !== null', timeout=90000)
                page.wait_for_timeout(2500)
                m = page.evaluate("""()=>{const d=AI.mask,w=AI.mw,h=AI.mh,at=(x,y)=>d[Math.floor(y*h)*w+Math.floor(x*w)];
                    const row=(a,b)=>{let s=0;for(let i=Math.floor(a*h)*w;i<Math.floor(b*h)*w;i++)s+=d[i]>127;return s/((b-a)*h*w)};
                    return {area:d.filter(v=>v>127).length/d.length,centre:at(.5,.6),top:row(0,.2),bottom:row(.8,1),corner:(at(.02,.02)+at(.97,.02))/2,tap:at(aim.x,aim.y),failed:AI.failed}}""")
                ok = 0.02 < m['area'] < 0.9 and not m['failed']
                if kind == 'person':
                    # shoulders fill the bottom of this photo, so an upside-down mask is caught here
                    ok = ok and m['centre'] > 127 and m['corner'] < 127 and m['bottom'] > m['top']
                    still = page.evaluate("""async()=>{const o=await ask('fine',video,video.videoWidth,video.videoHeight),d=o.mask,w=o.w,h=o.h,
                        row=(a,b)=>{let s=0;for(let i=Math.floor(a*h)*w;i<Math.floor(b*h)*w;i++)s+=d[i]>127;return s/((b-a)*h*w)};
                        return {centre:d[Math.floor(.6*h)*w+(w>>1)],top:row(0,.2),bottom:row(.8,1)}}""")
                    check('still-portrait', still['centre'] > 127 and still['bottom'] > still['top'], str({k: round(v, 2) for k, v in still.items()}))
                else:
                    ok = ok and m['tap'] > 127
                check(kind, ok, str({k: round(v, 2) for k, v in m.items()}))
                mode = page.evaluate('[AI.mode, AI.workerOK]')
                check(kind + '-background-worker', mode == ['worker', True], str(mode))
        except Exception as e:
            check(kind, False, str(e).split('\n')[0] + ' | ' + page.evaluate("document.getElementById('msg').textContent"))
        page.close()
    browser.close()

summary = ' ; '.join(results + errors[:6]).replace('\n', ' ')[:3500]
print(f'::notice title=ai-test::{summary}')
sys.exit(1 if failed else 0)
