"""Sync Android :app strings.xml resources into KMP composeResources.

Reads values/strings.xml, values-en/strings.xml, and values-ms/strings.xml from
android-companion/app/src/main/res/ and writes clean, validated Compose
Multiplatform strings.xml files into
android-companion/shared/src/commonMain/composeResources/.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

REPO_ROOT = Path(__file__).resolve().parent.parent
APP_RES_DIR = REPO_ROOT / "android-companion" / "app" / "src" / "main" / "res"
KMP_RES_DIR = (
    REPO_ROOT
    / "android-companion"
    / "shared"
    / "src"
    / "commonMain"
    / "composeResources"
)
LOCALES: tuple[str, ...] = ("values", "values-en", "values-ms")


def parse_strings_xml(xml_path: Path) -> list[tuple[str, str]]:
    """Parse an Android strings.xml file into ordered (name, text) pairs.

    Raises ValueError if the root tag is not <resources>, if any <string>
    element is missing a name attribute, or if duplicate keys are found.
    """
    tree = ET.parse(xml_path)
    root = tree.getroot()
    if root.tag != "resources":
        raise ValueError(
            f"{xml_path}: expected root <resources>, got <{root.tag}>"
        )

    entries: list[tuple[str, str]] = []
    seen: set[str] = set()
    duplicates: list[str] = []

    for child in root:
        if child.tag != "string":
            raise ValueError(
                f"{xml_path}: unsupported tag <{child.tag}> in strings.xml"
            )
        if len(child) > 0:
            raise ValueError(
                f"{xml_path}: nested tags inside <string name={child.attrib.get('name')!r}>"
            )
        name = (child.attrib.get("name") or "").strip()
        if not name:
            raise ValueError(f"{xml_path}: <string> element missing name attribute")
        if name in seen:
            duplicates.append(name)
            continue
        seen.add(name)
        entries.append((name, child.text or ""))

    if duplicates:
        dup_list = ", ".join(sorted(set(duplicates)))
        raise ValueError(f"{xml_path}: duplicate string keys found: {dup_list}")

    return entries


def build_compose_strings_xml(entries: list[tuple[str, str]]) -> str:
    """Serialize (name, text) pairs into a clean UTF-8 Compose strings.xml."""
    root = ET.Element("resources")
    for name, text in entries:
        elem = ET.SubElement(root, "string", {"name": name})
        elem.text = text

    ET.indent(root, space="    ")
    body = ET.tostring(root, encoding="unicode")
    return f'<?xml version="1.0" encoding="utf-8"?>\n{body}\n'


def sync_locale(src_xml: Path, dst_xml: Path) -> int:
    """Sync a single locale strings.xml file and return the number of keys."""
    entries = parse_strings_xml(src_xml)
    xml_content = build_compose_strings_xml(entries)
    dst_xml.parent.mkdir(parents=True, exist_ok=True)
    dst_xml.write_text(xml_content, encoding="utf-8", newline="\n")
    return len(entries)


def sync_all(
    app_res_dir: Path = APP_RES_DIR,
    kmp_res_dir: Path = KMP_RES_DIR,
    locales: tuple[str, ...] = LOCALES,
) -> dict[str, int]:
    """Sync all configured locale strings.xml files from :app to :shared."""
    counts: dict[str, int] = {}
    for locale in locales:
        src = app_res_dir / locale / "strings.xml"
        dst = kmp_res_dir / locale / "strings.xml"
        counts[locale] = sync_locale(src, dst)
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Sync Android :app strings.xml into KMP composeResources."
    )
    parser.add_argument(
        "--app-res-dir",
        type=Path,
        default=APP_RES_DIR,
        help="Path to android-companion/app/src/main/res",
    )
    parser.add_argument(
        "--kmp-res-dir",
        type=Path,
        default=KMP_RES_DIR,
        help="Path to android-companion/shared/src/commonMain/composeResources",
    )
    args = parser.parse_args()

    counts = sync_all(args.app_res_dir, args.kmp_res_dir)
    for locale, count in counts.items():
        print(f"Synced {locale}/strings.xml ({count} strings)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
