#!/usr/bin/env python3
"""Copies happy-ios's generated vector tables into :icons and :layout.

happy-ios generates its icon outlines (scripts/generate-icons.py), the Seti
file icons (scripts/generate-file-icons.py) and the wordmark paths from
happy-app's sources. Android reads the very same tables, so both apps draw the
same outlines: this script lifts them out of the Swift files as plain text
resources. Rerun it after happy-ios regenerates them:

    python3 packages/happy-android/tools/icons/extract-ios-tables.py
"""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.normpath(os.path.join(HERE, '..', '..'))
IOS = os.path.normpath(os.path.join(ANDROID, '..', 'happy-ios', 'Sources'))


def swift_multiline(text, start):
    """The body of the `\"\"\"` literal opening at or after `start`."""
    open_at = text.index('"""', start) + 3
    close_at = text.index('"""', open_at)
    lines = text[open_at:close_at].strip('\n').split('\n')
    indent = min((len(l) - len(l.lstrip(' ')) for l in lines if l.strip()), default=0)
    return '\n'.join(l[indent:] for l in lines), close_at + 3


def icons():
    src = open(os.path.join(IOS, 'HappyIcons', 'GeneratedIcons.swift')).read()
    version = re.search(r'generatedIconsVersion = "([^"]+)"', src).group(1)
    checksum = re.search(r'generatedIconsChecksum: UInt64 = 0x([0-9A-F]+)', src).group(1)
    out = [f'# version {version}', f'# checksum {checksum}']
    pos = 0
    pattern = re.compile(r'GeneratedFont\(\s*family: \.(\w+),\s*unitsPerEm: (-?\d+),\s*ascender: (-?\d+),\s*descender: (-?\d+),\s*glyphs:')
    while True:
        m = pattern.search(src, pos)
        if not m:
            break
        body, pos = swift_multiline(src, m.end())
        out.append(f'@font {m.group(1)} {m.group(2)} {m.group(3)} {m.group(4)}')
        out.append(body)
    path = os.path.join(ANDROID, 'icons', 'src', 'main', 'resources', 'com', 'slopus', 'happy', 'icons', 'glyphs.txt')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'w').write('\n'.join(out) + '\n')


def file_icons():
    src = open(os.path.join(IOS, 'HappyIcons', 'GeneratedFileIcons.swift')).read()
    out = []
    pos = 0
    for m in re.finditer(r'static let (\w+)(?:: [^=]+)? = """', src):
        body, _ = swift_multiline(src, m.start())
        out.append(f'@table {m.group(1)}')
        out.append(body)
    path = os.path.join(ANDROID, 'icons', 'src', 'main', 'resources', 'com', 'slopus', 'happy', 'icons', 'file-icons.txt')
    open(path, 'w').write('\n'.join(out) + '\n')


def wordmark():
    src = open(os.path.join(IOS, 'HappyLayout', 'Wordmark.swift')).read()
    out = []
    for name in ('happyPath', 'engineeringPath'):
        m = re.search(r'static let ' + name + r': String = \[(.*?)\]\.joined\(\)', src, re.S)
        parts = re.findall(r'"([^"]*)"', m.group(1))
        out.append(f'{name} ' + ''.join(parts))
    path = os.path.join(ANDROID, 'layout', 'src', 'main', 'resources', 'com', 'slopus', 'happy', 'layout', 'wordmark.txt')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'w').write('\n'.join(out) + '\n')


icons()
file_icons()
wordmark()
print('ok')
