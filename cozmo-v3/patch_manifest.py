#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android"
A = "{" + ANDROID + "}"
ET.register_namespace("android", ANDROID)

manifest = Path(sys.argv[1])
apktool_yml = Path(sys.argv[2]) if len(sys.argv) > 2 else None

tree = ET.parse(manifest)
root = tree.getroot()

uses_sdk = root.find("uses-sdk")
if uses_sdk is not None:
    old_target = uses_sdk.get(A + "targetSdkVersion")
    uses_sdk.set(A + "targetSdkVersion", "29")
    print(f"manifest targetSdkVersion: {old_target} -> 29")
else:
    print("uses-sdk absent du manifeste decode (normal avec Apktool 3); cible modifiee dans apktool.yml")

app = root.find("application")
if app is None:
    raise SystemExit("application introuvable")

app.set(A + "allowNativeHeapPointerTagging", "false")
app.set(A + "requestLegacyExternalStorage", "true")
tree.write(manifest, encoding="utf-8", xml_declaration=True)

if apktool_yml is not None:
    y = apktool_yml.read_text(encoding="utf-8")
    new, n = re.subn(
        r"(?m)^(\s*targetSdkVersion:\s*)['\"]?35['\"]?\s*$",
        r"\g<1>'29'",
        y,
    )
    if n != 1:
        raise SystemExit(f"targetSdkVersion 35 introuvable/ambigu dans {apktool_yml} (matches={n})")
    apktool_yml.write_text(new, encoding="utf-8")
    print("apktool.yml targetSdkVersion: 35 -> 29")

print("android:allowNativeHeapPointerTagging=false")
print("android:requestLegacyExternalStorage=true")
