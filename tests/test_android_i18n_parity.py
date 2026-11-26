"""Parity and integrity tests for Android :app and KMP composeResources strings.xml."""

from __future__ import annotations

from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET

import pytest

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
LOCALES = ("values", "values-en", "values-ms")


def _load_strings(xml_path: Path) -> tuple[dict[str, str], list[str]]:
    assert xml_path.is_file(), f"Missing strings resource file: {xml_path}"
    tree = ET.parse(xml_path)
    root = tree.getroot()
    assert root.tag == "resources", f"{xml_path}: unexpected root tag <{root.tag}>"

    names: list[str] = []
    mapping: dict[str, str] = {}
    for child in root:
        assert child.tag == "string", f"{xml_path}: unexpected tag <{child.tag}>"
        name = (child.attrib.get("name") or "").strip()
        assert name, f"{xml_path}: <string> missing name attribute"
        names.append(name)
        mapping[name] = child.text or ""

    duplicates = sorted(k for k, count in Counter(names).items() if count > 1)
    return mapping, duplicates


@pytest.mark.parametrize("locale", LOCALES)
def test_app_strings_valid_and_no_duplicates(locale: str) -> None:
    xml_path = APP_RES_DIR / locale / "strings.xml"
    mapping, duplicates = _load_strings(xml_path)
    assert mapping, f"{xml_path} has no <string> entries"
    assert not duplicates, f"{xml_path} has duplicate keys: {duplicates}"


def test_app_strings_cross_locale_parity() -> None:
    locale_keys: dict[str, set[str]] = {}
    for locale in LOCALES:
        mapping, _ = _load_strings(APP_RES_DIR / locale / "strings.xml")
        locale_keys[locale] = set(mapping.keys())

    base_keys = locale_keys["values"]
    for locale in LOCALES[1:]:
        missing = sorted(base_keys - locale_keys[locale])
        extra = sorted(locale_keys[locale] - base_keys)
        assert not missing, f":app {locale}/strings.xml missing keys: {missing}"
        assert not extra, f":app {locale}/strings.xml has extra keys: {extra}"


@pytest.mark.parametrize("locale", LOCALES)
def test_kmp_compose_resources_valid_and_matches_app(locale: str) -> None:
    app_map, app_dups = _load_strings(APP_RES_DIR / locale / "strings.xml")
    kmp_map, kmp_dups = _load_strings(KMP_RES_DIR / locale / "strings.xml")

    assert not app_dups
    assert not kmp_dups, f"composeResources {locale}/strings.xml has duplicate keys: {kmp_dups}"

    missing = sorted(set(app_map) - set(kmp_map))
    extra = sorted(set(kmp_map) - set(app_map))
    assert not missing, f"composeResources {locale}/strings.xml missing keys: {missing}"
    assert not extra, f"composeResources {locale}/strings.xml has extra keys: {extra}"

    mismatched = [k for k in app_map if app_map[k] != kmp_map[k]]
    assert not mismatched, (
        f"composeResources {locale}/strings.xml values differ from :app for keys: "
        f"{mismatched[:10]}"
    )


def test_kmp_compose_resources_cross_locale_parity() -> None:
    locale_keys: dict[str, set[str]] = {}
    for locale in LOCALES:
        mapping, _ = _load_strings(KMP_RES_DIR / locale / "strings.xml")
        locale_keys[locale] = set(mapping.keys())

    base_keys = locale_keys["values"]
    for locale in LOCALES[1:]:
        missing = sorted(base_keys - locale_keys[locale])
        extra = sorted(locale_keys[locale] - base_keys)
        assert not missing, f"composeResources {locale}/strings.xml missing keys: {missing}"
        assert not extra, f"composeResources {locale}/strings.xml has extra keys: {extra}"
