#!/usr/bin/env python3
"""Drafts a JUnit test file from a happy-ios HappyLayoutTests XCTest file.

The draft is a starting point, not a finished port: it rewrites the syntax
that is mechanical (declarations, literals, argument labels, XCTest
asserts, which layout's Support.kt mirrors by name) and leaves the rest to
be fixed by hand against the compiler. Usage, from packages/happy-android:

    python3 tools/port/swift-test-to-kotlin.py ItemLayoutTests.swift layout/src/test/kotlin/com/slopus/happy/layout/ItemLayoutTest.kt
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

IOS_TESTS = Path("../happy-ios/Tests/HappyLayoutTests")


INT_NAMES = {"maxLines", "count", "lines", "index", "fieldLines", "columns", "rows", "limit", "line", "column"}


def doubles(match: re.Match) -> str:
    name, number = match.group(1), match.group(2)
    return f"{name} = {number}" if name in INT_NAMES else f"{name} = {number}.0"


TYPES = {"Bool": "Boolean", "CGFloat": "Double", "CGSize": "Size", "CGRect": "Rect", "CGPoint": "Point"}


def kotlin_type(t: str) -> str:
    t = t.strip()
    m = re.match(r"^\[(.*)\](\??)$", t)
    if m:
        return f"List<{kotlin_type(m.group(1))}>{m.group(2)}"
    return TYPES.get(t.rstrip("?"), t.rstrip("?")) + ("?" if t.endswith("?") else "")


def declaration(line: str) -> str | None:
    m = re.match(r"^(\s*)(?:private |static |fileprivate )*func (\w+)\((.*)\)(?:\s*->\s*(.+?))?\s*\{\s*$", line)
    if not m or m.group(2).startswith("test"):
        return None
    indent, name, params, result = m.groups()
    out = []
    for param in filter(None, (p.strip() for p in params.split(","))):
        pm = re.match(r"^(?:_ )?(\w+)(?: \w+)?: ([^=]+?)(?: = (.+))?$", param)
        if not pm:
            out.append(param)
            continue
        pname, ptype, default = pm.groups()
        ktype = kotlin_type(ptype)
        if default is not None:
            default = re.sub(r"\bnil\b", "null", default)
            if ktype == "Double" and re.fullmatch(r"-?\d+", default):
                default += ".0"
            out.append(f"{pname}: {ktype} = {default}")
        else:
            out.append(f"{pname}: {ktype}")
    ret = f": {kotlin_type(result)}" if result else ""
    return f"{indent}fun {name}({', '.join(out)}){ret} {{"


def brackets(text: str) -> str:
    """Array literals become listOf(...), subscripts stay."""
    out, stack, prev = [], [], ""
    in_string = False
    for ch in text:
        if ch == '"':
            in_string = not in_string
        if in_string:
            out.append(ch)
        elif ch == "[":
            literal = prev == "" or prev in "(=,:[ \n"
            stack.append(literal)
            out.append("listOf(" if literal else "[")
        elif ch == "]" and stack:
            out.append(")" if stack.pop() else "]")
        else:
            out.append(ch)
        if not ch.isspace() or ch == "\n":
            prev = ch if ch != "\n" else " "
        elif prev not in "(=,:[":
            prev = " " if prev == "" else prev
    return "".join(out)


def list_literal(text: str) -> str:
    """`[a, b]` as `listOf(a, b)`; anything else as it is."""
    text = text.strip()
    if text.startswith("[") and text.endswith("]"):
        return f"listOf({text[1:-1]})"
    return text


def convert_line(line: str) -> str:
    s = line
    if s.lstrip().startswith("//"):
        return s
    decl = declaration(s)
    if decl is not None:
        return decl
    s = re.sub(r"\bfunc (test\w*)\(\)", r"@Test fun \1()", s)
    s = re.sub(r"\bfunc (\w+)\(", r"fun \1(", s)
    s = re.sub(r"\)\s*->\s*([\w.<>\[\]?]+)\s*\{", r"): \1 {", s)
    s = re.sub(r"\blet\b", "val", s)
    s = re.sub(r"(\w+)\(\.init\(", r"\1(\1.Input(", s)
    s = s.replace("CGFloat", "Double").replace("CGSize", "Size").replace("CGRect", "Rect").replace("CGPoint", "Point")
    s = re.sub(r"Size\(width: ([^,]+), height: ([^)]+)\)", r"Size(\1, \2)", s)
    s = re.sub(r"Point\(x: ([^,]+), y: ([^)]+)\)", r"Point(\1, \2)", s)
    s = re.sub(r'String\(repeating: ("[^"]*"), count: ([^)]+)\)', r"\1.repeat(\2)", s)
    s = s.replace("??", "?:")
    s = s.replace(".zero", "Size.Zero")
    s = re.sub(r"\(fitting: ", "(", s)
    s = re.sub(r"\bfor (\w+) in ", r"for (\1 in ", s)
    s = re.sub(r"(?<![\w\]])\[\]", "emptyList()", s)
    # Typed declarations keep their colon; argument labels become named arguments.
    s = re.sub(r"\b(val|var) (\w+): ", lambda m: f"{m.group(1)} {m.group(2)}\0 ", s)
    s = re.sub(r"(?<=[(,\s])([a-z]\w*): (?=[^\s])", r"\1 = ", s)
    s = s.replace("\0", ":")
    s = re.sub(r"\bnil\b", "null", s)
    s = re.sub(r"\.count\b(?!\()", ".size", s)
    s = re.sub(r"(\w)!(?=[,)\s.])", r"\1!!", s)
    s = re.sub(r"\bfor \((\w+) in (.+) \{$", lambda m: f"for ({m.group(1)} in {list_literal(m.group(2))}) {{", s)
    s = re.sub(r"\{ (\w+|_|\(\w+, \w+\)) in\b", lambda m: "{ " + m.group(1).strip("()") + " ->", s)
    s = re.sub(r"\b(?:Double|CGFloat)\(([^()]*)\)", r"(\1).toDouble()", s)
    s = s.replace("$0", "it")
    # A whole number passed by name is a Double unless the name is a count.
    s = re.sub(r"\b([a-z]\w*) = (-?\d+)(?![\d.])", doubles, s)
    return s


def convert(text: str) -> str:
    out = []
    returns = None
    for line in text.splitlines():
        if line.startswith(("import ", "#if", "#endif")):
            continue
        # A helper returning `.init(...)` names its type in the declaration.
        m = re.match(r".*\bfunc \w+\(.*\)\s*->\s*([\w.]+)\s*\{", line)
        if m:
            returns = m.group(1)
        elif returns and line.strip().startswith(".init("):
            line = line.replace(".init(", "return " + returns + "(", 1)
            returns = None
        line = re.sub(r"^final class (\w+)Tests: XCTestCase \{", r"class \1Test {", line)
        line = re.sub(r"^final class (\w+): XCTestCase \{", r"class \1 {", line)
        out.append(convert_line(line))
    body = brackets("\n".join(out).strip() + "\n")
    return (
        "package com.slopus.happy.layout\n\n"
        "import org.junit.Test\n\n"
        "// Ported from happy-ios Tests/HappyLayoutTests.\n\n" + body
    )


def main() -> None:
    source = Path(sys.argv[1])
    if not source.exists():
        source = IOS_TESTS / sys.argv[1]
    target = Path(sys.argv[2])
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(convert(source.read_text()))


if __name__ == "__main__":
    main()
