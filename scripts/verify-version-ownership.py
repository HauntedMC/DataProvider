#!/usr/bin/env python3
"""Keep external coordinates and Maven plugin versions owned by HauntedPlatform."""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
INTERNAL_PROPERTIES = {
    "haunted.dataprovider.version",
    "haunted.dataregistry.version",
    "haunted.featureframework.version",
    "haunted.observability.version",
    "haunted.theme.version",
    "haunted.theme.palette.version",
    "haunted.theme.adapter.version",
    "haunted.proxyfeatures.contracts.version",
}
errors = []
for path in Path(".").rglob("pom.xml"):
    if "target" in path.parts or ".mvn" in path.parts:
        continue
    root = ET.parse(path).getroot()
    parent_version = root.findtext("m:parent/m:version", default="", namespaces=NS)
    properties = root.find("m:properties", NS)
    if properties is not None:
        for child in properties:
            name = child.tag.rsplit("}", 1)[-1]
            value = (child.text or "").strip()
            if name in INTERNAL_PROPERTIES or not re.match(r"^\d", value):
                continue
            external_key = (
                name.startswith("haunted.") and name.endswith(".version")
                or name.startswith("maven.")
                or name.endswith(".plugin.version")
            )
            # Transitional DataProvider 3.4.4 still inherits Platform 1.6.10,
            # which did not manage build-helper. Platform 2.0.0 does.
            legacy_helper = (
                name == "build.helper.maven.plugin.version"
                and path == Path("pom.xml")
                and parent_version == "1.6.10"
            )
            if external_key and not legacy_helper:
                errors.append(f"{path}: external version override {name}={value}")
    for dependency in root.findall(".//m:dependency", NS):
        group = dependency.findtext("m:groupId", default="", namespaces=NS)
        version = dependency.findtext("m:version", default="", namespaces=NS)
        if version and not (group.startswith("nl.hauntedmc") or group == "${project.groupId}"):
            if not re.fullmatch(r"\$\{haunted\.[\w.]+\}", version):
                errors.append(f"{path}: external dependency {group} has local version {version}")
    for plugin in root.findall(".//m:plugin", NS):
        version = plugin.findtext("m:version", default="", namespaces=NS)
        if version and re.match(r"^\d", version):
            errors.append(f"{path}: Maven plugin {plugin.findtext('m:artifactId', namespaces=NS)} has literal version {version}")
if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
print("External dependency and Maven plugin versions remain Platform-owned.")
