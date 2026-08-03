from pathlib import Path

root = Path("backend/src/main/java")

extensions = {
    ".java",
    ".properties",
    ".yml",
    ".yaml"
}

fixed = []
failed = []

for path in root.rglob("*"):
    if not path.is_file():
        continue

    if path.suffix.lower() not in extensions:
        continue

    data = path.read_bytes()

    if data.startswith(b"\xef\xbb\xbf"):
        data = data[3:]

    try:
        text = data.decode("utf-8")
        old_encoding = "utf-8"
    except UnicodeDecodeError:
        try:
            text = data.decode("gbk")
            old_encoding = "gbk"
        except UnicodeDecodeError as e:
            failed.append((str(path), str(e)))
            continue

    path.write_text(text, encoding="utf-8", newline="")
    fixed.append((str(path), old_encoding))

print("Fixed files:")
for file, enc in fixed:
    print(file + " <- " + enc)

if failed:
    print("")
    print("Failed files:")
    for file, err in failed:
        print(file + " : " + err)