"""Check that the public EMI API stays compatible with addons.

Three modes:

    python api_compat.py <baseline.jar> <current.jar>   # compare two jars
    python api_compat.py --dump <jar> <out.txt>         # write a signature baseline
    python api_compat.py --check <jar> [baseline.txt]   # verify a jar against a baseline

Only `dev/emi/emi/api/**` counts as API. Public and protected members are compared by their
declaration text and JVM descriptor, so removing, renaming, re-typing, hiding or making a member
final all count as incompatibilities. Private members and lambdas are ignored (pass --all to keep
them). The check exits with status 1 when the API becomes incompatible.

The committed baseline lives next to this script in `scripts/api-baseline-1.21.1.txt`; regenerate it deliberately with
`--dump` after an intentional API change and review the diff of that file.
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

JAVAP = "javap"

HEADER = re.compile(r'^(public|protected)?\s*(final\s+|abstract\s+|sealed\s+|non-sealed\s+)*(class|interface|enum|record)\b')
VOLATILE_MODIFIERS = re.compile(r'\b(final|synchronized|native|strictfp)\b\s*')
DEFAULT_BASELINE = str(Path(__file__).with_name("api-baseline-1.21.1.txt"))


def class_names(jar):
    with zipfile.ZipFile(jar) as z:
        return sorted(
            n[:-6].replace("/", ".")
            for n in z.namelist()
            if n.startswith("dev/emi/emi/api/") and n.endswith(".class")
        )


def javap(jar, name):
    out = subprocess.run(
        [JAVAP, "-p", "-s", "-cp", str(jar), name],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return out.stdout or ""


def parse(text):
    header = None
    members = []
    pending = None
    for raw in text.splitlines():
        s = raw.strip()
        if not s or s.startswith("Compiled from"):
            continue
        if s.startswith("descriptor:"):
            if pending is not None:
                members.append((pending, s))
                pending = None
            continue
        if s in ("{", "}"):
            continue
        if header is None and HEADER.match(s):
            header = s
            continue
        pending = s
    return header, members


def normalise(decl):
    return re.sub(r'\s+', ' ', VOLATILE_MODIFIERS.sub('', decl)).strip()


def interesting(decl, show_all):
    if show_all:
        return True
    if re.search(r'\blambda\$', decl):
        return False
    return decl.startswith("public") or decl.startswith("protected")


def collect(jar, show_all=False):
    """Return {class: {'header': str, 'members': set((decl, desc)), 'finals': set(decl)}}."""
    data = {}
    for name in class_names(jar):
        header, members = parse(javap(jar, name))
        kept, finals = set(), set()
        for decl, desc in members:
            if not interesting(decl, show_all):
                continue
            norm = normalise(decl)
            kept.add((norm, desc))
            if re.search(r'\bfinal\b', decl):
                finals.add(norm)
        data[name] = {
            "header": normalise(header) if header else "",
            "members": kept,
            "finals": finals,
        }
    return data


def dump(data, path):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for name in sorted(data):
            entry = data[name]
            f.write(f"class {name}\n")
            f.write(f"  header {entry['header']}\n")
            for decl, desc in sorted(entry["members"]):
                f.write(f"  member {decl} | {desc}\n")
            for decl in sorted(entry["finals"]):
                f.write(f"  final {decl}\n")
    print(f"wrote {path} ({len(data)} classes)")


def load(path):
    data = {}
    current = None
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line:
            continue
        if line.startswith("class "):
            current = line[6:]
            data[current] = {"header": "", "members": set(), "finals": set()}
        elif line.startswith("  header "):
            data[current]["header"] = line[9:]
        elif line.startswith("  member "):
            body = line[9:]
            decl, _, desc = body.rpartition(" | ")
            data[current]["members"].add((decl, desc))
        elif line.startswith("  final "):
            data[current]["finals"].add(line[8:])
    return data


def compare(base, cur, label_base, label_cur):
    removed = sorted(set(base) - set(cur))
    added = sorted(set(cur) - set(base))
    print(f"classes {label_base}={len(base)} {label_cur}={len(cur)}")
    for n in removed:
        print("  removed class:", n)
    for n in added:
        print("  added class:", n)

    header_changes, member_changes, final_changes = [], [], []
    for name in sorted(set(base) & set(cur)):
        b, c = base[name], cur[name]
        if b["header"] != c["header"]:
            header_changes.append((name, b["header"], c["header"]))
        missing = sorted(b["members"] - c["members"])
        if missing:
            member_changes.append((name, missing))
        new_finals = sorted(c["finals"] - b["finals"])
        if new_finals:
            final_changes.append((name, new_finals))

    for name, bh, ch in header_changes:
        print("  header change:", name)
        print("    was:", bh)
        print("    now:", ch)
    for name, missing in member_changes:
        print("  removed members:", name)
        for decl, desc in missing:
            print("     -", decl, "|", desc)
    for name, decls in final_changes:
        print("  newly final:", name)
        for decl in decls:
            print("     -", decl)

    ok = not (removed or header_changes or member_changes or final_changes)
    print("API compatible" if ok else "API BREAK DETECTED")
    return ok


def main():
    args = [a for a in sys.argv[1:]]
    show_all = "--all" in args
    args = [a for a in args if a != "--all"]

    if args and args[0] == "--dump":
        dump(collect(args[1], show_all), args[2])
        return 0
    if args and args[0] == "--check":
        baseline = args[2] if len(args) > 2 else DEFAULT_BASELINE
        return 0 if compare(load(baseline), collect(args[1], show_all), "baseline", "current") else 1
    if len(args) != 2:
        print(__doc__)
        return 2
    return 0 if compare(collect(args[0], show_all), collect(args[1], show_all), "baseline", "current") else 1


if __name__ == "__main__":
    sys.exit(main())
