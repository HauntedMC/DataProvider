#!/usr/bin/env python3
"""Decide whether a main-branch commit introduces an unpublished release."""
import argparse
import os
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def version(xml):
    root = ET.fromstring(xml)
    value = root.findtext("m:properties/m:revision", namespaces=NS)
    if value is None:
        value = root.findtext("m:version", namespaces=NS)
    if not value or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", value):
        raise SystemExit(f"Expected a release version in the POM, got {value!r}")
    return value


parser = argparse.ArgumentParser()
parser.add_argument("pom", nargs="?", default="pom.xml")
parser.add_argument("--tag-prefix", default="")
args = parser.parse_args()
path = Path(args.pom)
current = version(path.read_text())
tag = f"{args.tag_prefix}v{current}"
before = os.environ.get("GITHUB_EVENT_BEFORE", "")
force = os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
previous = None
if before and before != "0" * 40:
    old = subprocess.run(
        ["git", "show", f"{before}:{path.as_posix()}"],
        capture_output=True, text=True, check=False,
    )
    if old.returncode == 0:
        previous = version(old.stdout)
exists = subprocess.run(
    ["git", "show-ref", "--verify", "--quiet", f"refs/tags/{tag}"],
    check=False,
).returncode == 0
publish = not exists and (force or previous != current)
print(f"Current: {current}; previous: {previous}; tag: {tag}; publish: {publish}")
if os.environ.get("GITHUB_OUTPUT"):
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"version={current}\ntag={tag}\npublish={str(publish).lower()}\n")
