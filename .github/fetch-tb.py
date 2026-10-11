#!/usr/bin/env python3
"""Fetches Traveler's Backpack (Modrinth: travelersbackpack) and its required dependencies for CI.

Usage: fetch-tb.py <minecraft version> <out dir> <log dir>

Writes <out>/compile/*.jar (the newest Fabric build for this game version, else the newest Fabric build at all, to
compile SIFTEC's optional compat against) and <out>/runtime/*.jar (only builds made for this game version: what the
smoke server and client load). The version list, the chosen files and the dependencies go to <log>/tb/versions.txt.
"""
import json
import os
import sys
import urllib.parse
import urllib.request

API = "https://api.modrinth.com/v2"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "meakaandre-tech/SIFTEC CI"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def versions(project, loader="fabric"):
    q = urllib.parse.urlencode({"loaders": json.dumps([loader])})
    return json.loads(get(f"{API}/project/{project}/version?{q}"))


def main():
    mc, out, log = sys.argv[1], sys.argv[2], os.path.join(sys.argv[3], "tb")
    for d in (os.path.join(out, "compile"), os.path.join(out, "runtime"), log):
        os.makedirs(d, exist_ok=True)
    report = []

    def fetch(project, depth=0, seen=set()):
        if project in seen:
            return
        seen.add(project)
        try:
            proj = json.loads(get(f"{API}/project/{project}"))
            vs = versions(project)
        except Exception as e:  # noqa: BLE001
            report.append(f"{'  ' * depth}{project}: could not list ({e})")
            return
        slug = proj.get("slug", project)
        if slug == "fabric-api":
            report.append(f"{'  ' * depth}fabric-api: the build's own (gradle.properties) is used")
            return
        if depth == 0:
            report.append(f"all Fabric versions of {slug}:")
            for v in vs[:40]:
                report.append(f"  {v['id']} {v['version_number']} {v['game_versions']} {v['date_published'][:10]}")
        exact = [v for v in vs if mc in v["game_versions"]]
        chosen = exact[0] if exact else (vs[0] if vs else None)
        if not chosen:
            report.append(f"{'  ' * depth}{slug}: no Fabric build")
            return
        f = next((x for x in chosen["files"] if x.get("primary")), chosen["files"][0])
        tag = "for " + mc if exact else "NOT for " + mc + " (newest Fabric build)"
        report.append(f"{'  ' * depth}{slug}: {chosen['version_number']} {tag} {f['url']}")
        data = get(f["url"])
        name = slug + ".jar"
        open(os.path.join(out, "compile", name), "wb").write(data)
        if exact:
            open(os.path.join(out, "runtime", name), "wb").write(data)
        for d in chosen.get("dependencies", []):
            report.append(f"{'  ' * depth}  dependency {d.get('dependency_type')}: project {d.get('project_id')} version {d.get('version_id')}")
            if d.get("dependency_type") == "required" and d.get("project_id"):
                fetch(d["project_id"], depth + 1)

    fetch("travelersbackpack")
    text = "\n".join(report)
    print(text)
    open(os.path.join(log, "versions.txt"), "w").write(text + "\n")


if __name__ == "__main__":
    main()
