#!/usr/bin/env python3
"""Merge a Gradle Maven repository into the Pages worktree without rewriting versions."""
import argparse
import hashlib
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET

VERSION = re.compile(r"(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?\Z")


def version_key(value):
    match = VERSION.fullmatch(value)
    if not match:
        raise ValueError(f"Unsupported release version: {value}")
    major, minor, patch, stage, number = match.groups()
    return int(major), int(minor), int(patch), {"alpha": 0, "beta": 1, "rc": 2, None: 3}[stage], int(number or 0)


def checksums(path):
    data = path.read_bytes()
    for algorithm in ("md5", "sha1", "sha256", "sha512"):
        Path(f"{path}.{algorithm}").write_text(hashlib.new(algorithm, data).hexdigest())


def stage(source, destination):
    source = source.resolve()
    destination = destination.resolve()
    if source == destination or source in destination.parents or destination in source.parents:
        raise ValueError("Source and destination must be separate directories.")
    for artifact in ("storepal-core", "storepal-android"):
        incoming = source / "app" / "storepal" / artifact
        versions = sorted((p for p in incoming.iterdir() if p.is_dir()), key=lambda p: version_key(p.name))
        if not versions:
            raise ValueError(f"No published versions found for {artifact}.")
        target = destination / "maven" / "app" / "storepal" / artifact
        target.mkdir(parents=True, exist_ok=True)
        for version in versions:
            published = target / version.name
            if published.exists():
                old = {p.relative_to(published): p.read_bytes() for p in published.rglob("*") if p.is_file()}
                new = {p.relative_to(version): p.read_bytes() for p in version.rglob("*") if p.is_file()}
                if old != new:
                    raise ValueError(f"Refusing to overwrite immutable version {artifact}:{version.name}.")
            else:
                shutil.copytree(version, published)
        all_versions = sorted((p.name for p in target.iterdir() if p.is_dir()), key=version_key)
        stable = [v for v in all_versions if "-" not in v]
        root = ET.Element("metadata")
        ET.SubElement(root, "groupId").text = "app.storepal"
        ET.SubElement(root, "artifactId").text = artifact
        info = ET.SubElement(root, "versioning")
        ET.SubElement(info, "latest").text = all_versions[-1]
        ET.SubElement(info, "release").text = stable[-1] if stable else all_versions[-1]
        entries = ET.SubElement(info, "versions")
        for version in all_versions:
            ET.SubElement(entries, "version").text = version
        ET.SubElement(info, "lastUpdated").text = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
        metadata = target / "maven-metadata.xml"
        ET.indent(root)
        ET.ElementTree(root).write(metadata, encoding="utf-8", xml_declaration=True)
        checksums(metadata)
    (destination / ".nojekyll").touch()
    (destination / "index.html").write_text("""<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>StorePal Android SDK</title><main><h1>StorePal Android SDK</h1>
<p>Native feedback, attachments, conversations, and release notes for Android.</p>
<p><a href="https://github.com/xiao99xiao/StorePalAndroid#readme">Source and integration guide</a> ·
<a href="https://github.com/xiao99xiao/StorePalAndroid/releases">Downloads and releases</a></p>
<h2>Gradle Maven repository</h2><pre>https://xiao99xiao.github.io/StorePalAndroid/maven</pre>
<p>Artifacts: app.storepal:storepal-core and app.storepal:storepal-android.</p></main></html>
""", encoding="utf-8")
    print(f"Maven repository staged at {destination / 'maven'}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("build/maven-repository"))
    parser.add_argument("--destination", type=Path, required=True)
    arguments = parser.parse_args()
    stage(arguments.source, arguments.destination)
