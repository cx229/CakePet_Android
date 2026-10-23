import os, re

LAYOUT_DIR = r"m:/Code/open_source/CakePet_Android/app/src/main/res/layout"
TINT = 'app:tint="@color/cp_title"'

pat = re.compile(
    r'<ImageView\b(?:(?!/>).)*?android:id="@\+id/(?:iv_back|btn_more)"(?:(?!/>).)*?/?>',
    re.DOTALL,
)

files = 0
ivs = 0
count = 0
for root, dirs, files_ in os.walk(LAYOUT_DIR):
    for fn in files_:
        if not fn.endswith(".xml"):
            continue
        files += 1
        path = os.path.join(root, fn)
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()
        ivs += len(pat.findall(text))
        def repl(m):
            tag = m.group(0)
            if "tint" in tag:
                return tag
            idx = tag.rfind(">")
            return tag[:idx] + " " + TINT + tag[idx:]
        newtext, n = pat.subn(repl, text)
        if n:
            with open(path, "w", encoding="utf-8") as f:
                f.write(newtext)
            count += n
            print(f"{os.path.relpath(path, LAYOUT_DIR)}: +{n}")
print(f"files={files} imageviews_matched={ivs} tinted={count}")
