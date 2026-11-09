#!/usr/bin/env python3
"""List item/fluid tags that EMI has no display name for.

EMI warns (`Untranslated tag #...`) when a tag survives the exclusion list but has neither a
registry-scoped name (`tag.item.minecraft.<path>`) nor a legacy fallback (`tag.minecraft.<path>`).
Run this against the client jar of the target Minecraft version after a port to find the names that
still need to be added to `en_us.json` (and `zh_cn.json`).

Example:
    python scripts/scan_untranslated_tags.py --client-jar %APPDATA%/PrismLauncher/libraries/com/mojang/minecraft/1.21.8/minecraft-1.21.8-client.jar
"""
import argparse
import json
import os
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(REPO, "xplat", "src", "main", "resources", "assets", "emi")
LANGS = os.path.join(ASSETS, "lang")
EXCLUSIONS = os.path.join(ASSETS, "tag", "exclusions")

TAG_DIRS = {
    "item": ("data/minecraft/tags/item/", "data/minecraft/tags/items/"),
    "fluid": ("data/minecraft/tags/fluid/", "data/minecraft/tags/fluids/"),
}


def load_json(path):
    with open(path, encoding="utf-8-sig") as handle:
        return json.load(handle)


def load_lang(name):
    path = os.path.join(LANGS, name)
    if not os.path.exists(path):
        return {}
    return load_json(path)


def load_exclusions():
    excluded = set()
    if not os.path.isdir(EXCLUSIONS):
        return excluded
    for name in sorted(os.listdir(EXCLUSIONS)):
        if not name.endswith(".json"):
            continue
        data = load_json(os.path.join(EXCLUSIONS, name))
        for key in ("item", "fluid", "exclusions"):
            excluded.update(data.get(key, []))
    return excluded


def collect_tags(client_jar, registry):
    tags = set()
    with zipfile.ZipFile(client_jar) as jar:
        prefixes = TAG_DIRS[registry]
        for name in jar.namelist():
            for prefix in prefixes:
                if name.startswith(prefix) and name.endswith(".json"):
                    tags.add("minecraft:" + name[len(prefix):-5])
    return tags


def translation_key(registry, tag_id):
    namespace, path = tag_id.split(":", 1)
    dotted = path.replace("/", ".")
    return [f"tag.{registry}.{namespace}.{dotted}", f"tag.{namespace}.{dotted}"]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--client-jar", required=True, help="Minecraft client jar of the target version")
    parser.add_argument("--registry", action="append", choices=sorted(TAG_DIRS), help="registry to scan (default: all)")
    args = parser.parse_args()

    if not os.path.isfile(args.client_jar):
        print(f"client jar not found: {args.client_jar}", file=sys.stderr)
        return 2

    english = load_lang("en_us.json")
    excluded = load_exclusions()
    missing_total = 0

    for registry in args.registry or sorted(TAG_DIRS):
        missing = []
        for tag_id in sorted(collect_tags(args.client_jar, registry)):
            if tag_id in excluded:
                continue
            if not any(key in english for key in translation_key(registry, tag_id)):
                missing.append(tag_id)
        missing_total += len(missing)
        print(f"== {registry}: {len(missing)} unnamed tags")
        for tag_id in missing:
            print(f"   {tag_id}")

    if missing_total:
        print(f"\nAdd names to en_us.json (and zh_cn.json) under tag.<registry>.minecraft.* or extend the exclusions.")
    else:
        print("\nAll scanned tags are named or excluded; the dev-mode warning list should be empty.")
    return 1 if missing_total else 0


if __name__ == "__main__":
    sys.exit(main())
