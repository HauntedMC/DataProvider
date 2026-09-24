#!/usr/bin/env python3
"""Resolve every deployed reactor coordinate from an empty Maven repository."""
import argparse
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}

parser = argparse.ArgumentParser()
parser.add_argument("version")
parser.add_argument("repository")
parser.add_argument("--module", help="Verify only one independently released module")
parser.add_argument("--list", action="store_true", help="Print expected coordinates without network access")
args = parser.parse_args()
root = Path(".")
pom_paths = [root / args.module / "pom.xml"] if args.module else [root / "pom.xml"]
if not args.module:
    parent = ET.parse(root / "pom.xml").getroot()
    pom_paths += [root / name.text / "pom.xml" for name in parent.findall("m:modules/m:module", NS)]

coordinates = []
for path in pom_paths:
    pom = ET.parse(path).getroot()
    artifact = pom.findtext("m:artifactId", namespaces=NS)
    if artifact.endswith("-platform-acceptance") or artifact == "serverfeatures-testkit":
        continue
    group = pom.findtext("m:groupId", namespaces=NS) or pom.findtext("m:parent/m:groupId", namespaces=NS)
    packaging = pom.findtext("m:packaging", default="jar", namespaces=NS)
    coordinates.append(f"{group}:{artifact}:{args.version}:{packaging}")

if not coordinates:
    raise SystemExit("No release coordinates found")
if args.list:
    print("\n".join(coordinates))
    raise SystemExit(0)
with tempfile.TemporaryDirectory(prefix="haunted-published-m2-") as temporary:
    local_repo = Path(temporary) / "repository"
    consumer_pom = Path(temporary) / "pom.xml"
    consumer_pom.write_text(
        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        '<modelVersion>4.0.0</modelVersion>'
        '<groupId>nl.hauntedmc.verification</groupId>'
        '<artifactId>published-resolution</artifactId><version>1</version>'
        '</project>'
    )
    for coordinate in coordinates:
        command = [
            "./mvnw" if Path("mvnw").is_file() else "mvn", "-B", "-ntp", "-U",
            "-f", str(consumer_pom),
            f"-Dmaven.repo.local={local_repo}",
            "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:get",
            f"-Dartifact={coordinate}", "-Dtransitive=false",
            f"-DremoteRepositories=github::default::https://maven.pkg.github.com/HauntedMC/{args.repository}",
        ]
        for attempt in range(6):
            result = subprocess.run(command, check=False)
            if result.returncode == 0:
                break
            if attempt == 5:
                raise SystemExit(f"Published artifact could not be resolved: {coordinate}")
            time.sleep(10)
        print(f"Resolved {coordinate}", flush=True)
