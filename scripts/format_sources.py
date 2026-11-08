"""Normalise Java indentation in this repository to four spaces (tab width 4).

Only leading whitespace, trailing whitespace and the final newline are touched, so the code keeps
its meaning. Lines inside text blocks are left alone because their indentation is significant.

Usage: python format_sources.py <root> [<root> ...]
"""
import sys
from pathlib import Path

TAB = 4
USE_TABS = '--tabs' in sys.argv


def leading_columns(prefix):
    tabs = 0
    spaces = 0
    for ch in prefix:
        if ch == '\t':
            tabs += 1
        elif ch == ' ':
            spaces += 1
        else:
            break
    return tabs * TAB + spaces


def normalise(path):
    text = path.read_text(encoding='utf-8')
    lines = text.split('\n')
    out = []
    in_text_block = False
    changed = False
    for line in lines:
        original = line
        stripped_right = line.rstrip()
        if stripped_right != line:
            changed = True
        body = stripped_right
        prefix_len = len(body) - len(body.lstrip(' \t'))
        prefix, rest = body[:prefix_len], body[prefix_len:]
        quotes = rest.count('"""')
        if in_text_block:
            out.append(original)
            if quotes % 2 == 1:
                in_text_block = False
            continue
        if quotes % 2 == 1:
            in_text_block = True
            out.append(original)
            continue
        if prefix:
            columns = leading_columns(prefix)
            if USE_TABS:
                new_prefix = '\t' * (columns // TAB) + ' ' * (columns % TAB)
            else:
                new_prefix = ' ' * columns
            if new_prefix != prefix:
                changed = True
            out.append(new_prefix + rest)
        else:
            out.append(body)
    result = '\n'.join(out)
    if not result.endswith('\n'):
        result += '\n'
        changed = True
    if changed:
        path.write_text(result, encoding='utf-8')
    return changed


def main():
    roots = [Path(r) for r in sys.argv[1:] if not r.startswith('--')] or [Path('.')]
    total = 0
    for root in roots:
        for path in sorted(root.rglob('*.java')):
            if any(part in ('build', '.gradle', 'run') for part in path.parts):
                continue
            if normalise(path):
                total += 1
    print(f'normalised {total} files')


if __name__ == '__main__':
    main()
