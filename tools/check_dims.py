import os, struct

base = r'm:\Code\open_source\CakePet_Android\app\src\main\assets'

def dims(p):
    with open(p, 'rb') as fh:
        fh.read(16)
        return struct.unpack('>II', fh.read(8))

for pack in ['芝麻酥', '芝麻球促销', '白酥', 'img']:
    d = os.path.join(base, pack)
    if not os.path.isdir(d):
        continue
    sizes = []
    count = 0
    for root, dirs, files in os.walk(d):
        for f in files:
            if f.lower().endswith('.png'):
                try:
                    w, h = dims(os.path.join(root, f))
                    sizes.append((w, h))
                    count += 1
                except Exception:
                    pass
    if sizes:
        ws = [s[0] for s in sizes]
        hs = [s[1] for s in sizes]
        print(pack, 'count=', count, 'w[min,max]=', min(ws), max(ws), 'h[min,max]=', min(hs), max(hs))
