import io, os, sys, zipfile, hashlib

APK = r"D:\DifferentRockets\game\android\build\outputs\apk\debug\android-debug.apk"
SRC = r"D:\DifferentRockets\game\core\assets"

z = zipfile.ZipFile(APK)
apk_names = set(z.namelist())

def sha(b):
    return hashlib.sha256(b).hexdigest()[:16]

mismatch, missing_apk, extra_apk, ok = [], [], [], 0
checked = 0
for root, dirs, files in os.walk(SRC):
    rel_dir = os.path.relpath(root, SRC).replace("\\", "/")
    if rel_dir.startswith(".."):
        continue
    for f in files:
        rel = f if rel_dir == "." else rel_dir + "/" + f
        if rel.startswith("bin/"):
            continue  # desktop-only build output inside assets
        checked += 1
        disk = io.open(os.path.join(root, f), "rb").read()
        if rel not in apk_names:
            missing_apk.append(rel)
            continue
        apk_b = z.read(rel)
        if sha(apk_b) != sha(disk):
            mismatch.append(rel)
        else:
            ok += 1

# lua freshness: version header present and not older than the round baseline.
# Headers are per-file modification stamps, so a round bumps only the files it
# touches; anything below the previous round's version means a stale APK copy.
MIN_VER = (2026, 7, 21)
ver_missing = []
for n in apk_names:
    if n.startswith("mods/") and n.endswith(".lua"):
        first = z.read(n).decode("utf-8", "replace").split("\n", 1)[0].strip()
        ver = None
        if first.startswith("-- v"):
            try:
                ver = tuple(int(p) for p in first[4:].split("."))
            except ValueError:
                ver = None
        if ver is None or ver < MIN_VER:
            ver_missing.append(n)

print("checked %d disk files" % checked)
print("OK identical: %d" % ok)
print("MISSING in apk: %d" % len(missing_apk))
for n in missing_apk: print("  " + n)
print("BYTE MISMATCH: %d" % len(mismatch))
for n in mismatch: print("  " + n)
print("lua with missing/stale version header (< v%04d.%02d.%02d) in apk: %d" % (MIN_VER + (len(ver_missing),)))
for n in ver_missing: print("  " + n)
print("RESULT:", "PASS" if not (missing_apk or mismatch or ver_missing) else "FAIL")
