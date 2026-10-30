"""Create the metadata asset consumed by the in-app GitHub updater."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("--channel", required=True, choices=["github-master", "github-release"])
parser.add_argument("--repository", required=True)
parser.add_argument("--version-code", required=True, type=int)
parser.add_argument("--version-name", required=True)
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
    parser.error("Invalid repository")
if not 0 < args.version_code <= 2147483647:
    parser.error("Invalid Android version code")
expected = "aarchive-master-universal.apk" if args.channel == "github-master" else "aarchive-universal.apk"
if args.apk.name != expected or not args.apk.is_file():
    parser.error("APK name does not match its channel")
sdk = os.environ.get("ANDROID_HOME")
if not sdk:
    parser.error("Set ANDROID_HOME so the APK's actual metadata can be verified")
aapt = Path(sdk) / "build-tools/35.0.0/aapt2"
badging = subprocess.check_output([str(aapt), "dump", "badging", str(args.apk)], text=True)
package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
if not package or package.groups() != ("dev.aarchive", str(args.version_code), args.version_name):
    parser.error("Package/version arguments do not match the APK")
tree = subprocess.check_output([str(aapt), "dump", "xmltree", "--file", "AndroidManifest.xml", str(args.apk)], text=True)
for key, expected_value in [("UPDATE_CHANNEL", args.channel), ("UPDATE_REPOSITORY", args.repository)]:
    match = re.search(r'android:name[^\n]*="dev\.aarchive\.' + key + r'"[^\n]*\n[^\n]*android:value[^\n]*="([^"]+)"', tree)
    if not match or match.group(1) != expected_value:
        parser.error("Update provenance arguments do not match the APK")
metadata = dict(schemaVersion=1, channel=args.channel, repository=args.repository,
                versionCode=args.version_code, versionName=args.version_name, apk=args.apk.name,
                size=args.apk.stat().st_size, sha256=hashlib.sha256(args.apk.read_bytes()).hexdigest())
(args.apk.parent / "update.json").write_text(json.dumps(metadata, indent=2) + "\n")
