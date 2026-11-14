#!/usr/bin/env python3
"""
One-shot refactor for Flow:
  1. renames the package / applicationId  io.github.aedev.flow  ->  app.zaid.flow
  2. moves every source folder to match   (io/github/aedev/flow -> app/zaid/flow)
  3. removes every GitHub link from text/source/docs files

Run from the project root (the folder that contains settings.gradle.kts):
    python3 rename_package.py                      # io.github.aedev.flow -> app.zaid.flow
    python3 rename_package.py OLD.PACKAGE NEW.PACKAGE   # any other rename
"""
import os, re, shutil, sys

OLD_PKG = sys.argv[1] if len(sys.argv) > 2 else "io.github.aedev.flow"
NEW_PKG = sys.argv[2] if len(sys.argv) > 2 else "app.zaid.flow"
OLD_DIR = os.path.join(*OLD_PKG.split("."))
NEW_DIR = os.path.join(*NEW_PKG.split("."))

SKIP_DIRS = {".git", "build", ".gradle", ".idea", "node_modules"}
TEXT_EXT = {".kt", ".kts", ".java", ".xml", ".pro", ".txt", ".md", ".json", ".yml", ".yaml",
            ".properties", ".gradle", ".toml", ".bat", ".html", ".cfg", ".py", ".sh"}

GITHUB_URL = re.compile(r"https?://(?:[\w.-]+\.)?github(?:usercontent)?\.(?:com|io)[^\s\"'<>)\]]*", re.I)
# whole lines that only exist to point at GitHub (licence headers, "based on" credits)
GITHUB_LINE = re.compile(r"^[ \t]*(?://|\*|/\*+|#)[^\n]*https?://[\w.-]*github[^\n]*\n", re.I | re.M)

def is_text(path):
    return os.path.splitext(path)[1].lower() in TEXT_EXT or os.path.basename(path) in {"gradlew", "License"}

def walk(root):
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
        for f in files:
            yield os.path.join(d, f)

def main(root="."):
    if not os.path.exists(os.path.join(root, "settings.gradle.kts")):
        sys.exit("Run this from the Flow project root (settings.gradle.kts not found).")

    # 1 + 3: rewrite file contents
    changed = 0
    for path in walk(root):
        if os.path.abspath(path) == os.path.abspath(__file__) or not is_text(path):
            continue
        try:
            s = open(path, encoding="utf-8").read()
        except (UnicodeDecodeError, OSError):
            continue
        n = s.replace(OLD_PKG, NEW_PKG).replace(OLD_DIR.replace(os.sep, "/"), NEW_DIR.replace(os.sep, "/"))
        n = GITHUB_LINE.sub("", n)
        n = GITHUB_URL.sub("", n)
        if n != s:
            open(path, "w", encoding="utf-8").write(n)
            changed += 1

    # 2: move the package folders (java/kotlin source sets, tests, generated profiles)
    moved = 0
    for d, dirs, _ in list(os.walk(root, topdown=False)):
        if any(p in SKIP_DIRS for p in d.split(os.sep)):
            continue
        if d.endswith(os.sep + OLD_DIR):
            target = d[: -len(OLD_DIR)] + NEW_DIR
            os.makedirs(os.path.dirname(target), exist_ok=True)
            if os.path.exists(target):
                for item in os.listdir(d):
                    shutil.move(os.path.join(d, item), os.path.join(target, item))
                os.rmdir(d)
            else:
                shutil.move(d, target)
            moved += 1
            # remove now-empty io/github/aedev parents
            parent = os.path.dirname(d)
            while os.path.basename(parent) in OLD_PKG.split(".") and not os.listdir(parent):
                os.rmdir(parent)
                parent = os.path.dirname(parent)
    print(f"Package is now {NEW_PKG}: {changed} files rewritten, {moved} source folders moved.")

if __name__ == "__main__":
    main()
