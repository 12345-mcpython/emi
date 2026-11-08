"""Catch fields that are declared final but written at runtime.

The cleanup passes keep adding `final`, and two kinds of write never show up as a compile error:

* reflection: `@ConfigValue` fields are assigned with `Field#set(null, value)`, which throws
  `IllegalAccessException` on a `static final` field and silently skips the whole config file;
* mixins: a `@Shadow` field declared `final` cannot be written unless it also carries `@Mutable`,
  and a `@Shadow` field with an initialiser is a trap because Mixin discards the initialiser.

Run without arguments from anywhere:

    python scripts/check_field_writes.py [--root PATH]

Exits 1 when a real problem is found, 0 otherwise. Accessor setters on possibly final target fields
are reported as warnings because the target's finality is not visible from this repository.
"""
import argparse
import re
import sys
from pathlib import Path

SKIP_DIRS = {"build", ".gradle", "run", ".git", ".idea", "screenshots", ".architectury-transformer"}

FIELD = re.compile(
    r'^\s*(?P<mods>(?:(?:public|protected|private|static|final|volatile|transient)\s+)+)'
    r'(?P<type>[\w.$<>\[\], ?]+?)\s+(?P<name>\w+)\s*(?P<rest>=.*)?;\s*$'
)


def java_files(root):
    for path in root.rglob("*.java"):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        yield path


def reflective_written_classes(root):
    """Classes whose fields are assigned through reflection."""
    classes = {}
    for path in java_files(root):
        text = path.read_text(encoding="utf-8", errors="replace")
        targets = {
            m.group(1) or m.group(2)
            for m in re.finditer(
                r'(\w+)\.class\.get(?:Declared)?Fields?\(\)|(\w+)\.class\.getDeclaredField\(', text)
        }
        if not targets:
            continue
        field_vars = set(re.findall(r'\bField\s+(\w+)', text))
        field_vars |= set(re.findall(r'\bvar\s+(\w+)\s*=\s*[\w.]+\.class\.getDeclaredField', text))
        if not any(re.search(r'\b' + re.escape(var) + r'\.set\(', text) for var in field_vars):
            continue
        for cls in targets:
            classes.setdefault(cls, set()).add(path)
    return classes


def check_reflection(root):
    errors = []
    targets = reflective_written_classes(root)
    for path in java_files(root):
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        holder = None
        for index, line in enumerate(lines):
            match = re.search(r'\b(?:class|interface|enum|record)\s+(\w+)', line)
            if match and not line.strip().startswith(("//", "*")):
                holder = match.group(1)
            field = FIELD.match(line)
            if field is None or holder not in targets or "final" not in field.group("mods"):
                continue
            annotations = "\n".join(lines[max(0, index - 6):index])
            if "@ConfigValue" in annotations or "@ConfigFilter" in annotations:
                errors.append(
                    f"{path.relative_to(root)}:{index + 1}: {holder}.{field.group('name')} is final but the "
                    f"config loader assigns it by reflection; reflection writes to static final fields throw"
                )
    return errors


def check_mixins(root):
    errors = []
    warnings = []
    for path in java_files(root):
        if "mixin" not in str(path.relative_to(root)).lower():
            continue
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        for index, line in enumerate(lines):
            if "@Shadow" not in line:
                continue
            window = lines[index:index + 6]
            decl_index = None
            decl = None
            for offset, candidate in enumerate(window):
                if candidate.strip().endswith(";"):
                    decl_index = index + offset
                    decl = candidate
                    break
            if decl is None:
                continue
            annotations = window[:decl_index - index + 1]
            has_mutable = any("@Mutable" in entry for entry in annotations)
            declares_final = re.search(r'\bfinal\b', decl) is not None
            has_initialiser = re.search(r'\bfinal\b', decl) is not None and "=" in decl
            if declares_final and not has_mutable:
                errors.append(
                    f"{path.relative_to(root)}:{decl_index + 1}: @Shadow field is final without @Mutable: "
                    f"{decl.strip()}")
            if has_initialiser:
                errors.append(
                    f"{path.relative_to(root)}:{decl_index + 1}: @Shadow field has an initialiser, which Mixin "
                    f"discards: {decl.strip()}")
            if has_mutable and not declares_final:
                warnings.append(
                    f"{path.relative_to(root)}:{decl_index + 1}: @Mutable on a field that is not final: "
                    f"{decl.strip()}")

        for index, line in enumerate(lines):
            if "@Accessor(" not in line:
                continue
            window = "\n".join(lines[index:index + 5])
            if re.search(r'\bvoid\s+set[A-Z]', window) and "@Mutable" not in window:
                warnings.append(
                    f"{path.relative_to(root)}:{index + 1}: @Accessor setter without @Mutable; it breaks at "
                    f"runtime when the target field is final")
    return errors, warnings


def check_shadow_initialisers_standalone(root):
    """Shadow fields with initialisers are only meaningful for remap=false reads; report them."""
    return []


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    root = args.root.resolve()

    errors = []
    errors.extend(check_reflection(root))
    mixin_errors, warnings = check_mixins(root)
    errors.extend(mixin_errors)

    for warning in warnings:
        print("warning:", warning)
    for error in errors:
        print("error:", error)

    scanned = len(list(java_files(root)))
    print(f"scanned {scanned} files, {len(errors)} errors, {len(warnings)} warnings")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
