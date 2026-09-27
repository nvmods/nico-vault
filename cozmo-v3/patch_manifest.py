#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
A = "{" + ANDROID + "}"
ET.register_namespace("android", ANDROID)

path = sys.argv[1]
tree = ET.parse(path)
root = tree.getroot()

uses_sdk = root.find("uses-sdk")
if uses_sdk is None:
    raise SystemExit("uses-sdk introuvable")

old_target = uses_sdk.get(A + "targetSdkVersion")
uses_sdk.set(A + "targetSdkVersion", "29")

app = root.find("application")
if app is None:
    raise SystemExit("application introuvable")

# Compatibilite visee pour les bibliotheques natives historiques de Cozmo.
app.set(A + "allowNativeHeapPointerTagging", "false")
app.set(A + "requestLegacyExternalStorage", "true")

tree.write(path, encoding="utf-8", xml_declaration=True)
print(f"targetSdkVersion: {old_target} -> 29")
print("android:allowNativeHeapPointerTagging=false")
print("android:requestLegacyExternalStorage=true")
