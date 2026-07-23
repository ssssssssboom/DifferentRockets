import io, os, sys

MODS = r"D:\DifferentRockets\game\core\assets\mods"
VER = "-- v2026.07.21"

changed, skipped = [], []
for name in sorted(os.listdir(MODS)):
    if not name.endswith(".lua"):
        continue
    path = os.path.join(MODS, name)
    src = io.open(path, encoding="utf-8").read()
    first = src.split("\n", 1)[0].strip()
    if first.startswith("-- v"):
        skipped.append(name)
        continue
    io.open(path, "w", encoding="utf-8", newline="").write(VER + "\n" + src)
    changed.append(name)

print("versioned %d files:" % len(changed))
for n in changed:
    print("  " + n)
print("skipped (already versioned): %d" % len(skipped))
for n in skipped:
    print("  " + n)
