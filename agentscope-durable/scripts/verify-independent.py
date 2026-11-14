#!/usr/bin/env python3
"""Build in the current checkout with an isolated cache containing no AgentScope/Managed artifacts."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

argparse.ArgumentParser(description=__doc__).parse_args()
ROOT = Path(__file__).resolve().parents[2]
repository = Path(tempfile.mkdtemp(prefix="agentscope-durable-maven-"))
cache = Path.home() / ".m2/repository"
# Seed immutable third-party jars, copying mutable metadata; never copy project/platform artifacts.
for directory, names, files in os.walk(cache):
    relative = Path(directory).relative_to(cache)
    names[:] = [
        n for n in names
        if relative / n not in {Path("io/agentscope"), Path("io/agentcore")}
        and "managed-agents" not in n
    ]
    target = repository / relative
    target.mkdir(parents=True, exist_ok=True)
    for name in files:
        source = Path(directory) / name
        if source.is_symlink() or "managed-agents" in name:
            continue
        if name.endswith(".jar"):
            try:
                os.link(source, target / name)
            except OSError:
                shutil.copy2(source, target / name)
        else:
            shutil.copy2(source, target / name)
revision = ET.parse(ROOT / "pom.xml").findtext("{*}properties/{*}revision")
if not revision:
    raise SystemExit("Missing root revision property")
common = ["mvn", "-B", "-ntp", "-T1", "-Dmaven.repo.local=" + str(repository), "-Drevision=" + revision]
def run(arguments):
    subprocess.run(common + arguments, cwd=ROOT, check=True)

print("Isolated Maven repository:", repository, flush=True)
for pom in ["agentscope-dependencies-bom/pom.xml", "agentscope-distribution/agentscope-bom/pom.xml",
            "pom.xml", "agentscope-examples/pom.xml", "agentscope-extensions/pom.xml",
            "agentscope-extensions/agentscope-extensions-protocol/pom.xml"]:
    run(["-N", "-f", pom, "-DskipTests", "install"])
for pom in ["agentscope-core/pom.xml",
            "agentscope-extensions/agentscope-extensions-protocol/agentscope-extensions-agui/pom.xml",
            "agentscope-harness/pom.xml"]:
    run(["-f", pom, "-DskipTests", "install"])
run(["-f", "agentscope-durable/pom.xml", "-Pdurable-standalone", "clean", "install"])
run(["-f", "agentscope-durable/pom.xml", "-Pdurable-standalone", "dependency:tree",
     "-DoutputFile=target/durable-dependencies.txt"])
areas = [ROOT / "agentscope-durable", ROOT / "agentscope-examples/durable",
         *ROOT.joinpath("agentscope-extensions").glob("agentscope-extensions-durable-*")]
for area in areas:
    for tree in area.glob("**/target/durable-dependencies.txt"):
        content = tree.read_text()
        if any(name in content for name in ("managed-agents", "io.agentcore")):
            raise SystemExit("Forbidden dependency: " + str(tree))
for forbidden in ["io/agentcore", "io/agentscope/managed-agents-common"]:
    if (repository / forbidden).exists():
        raise SystemExit("Forbidden cache artifact: " + forbidden)
print("Independent source build, tests and dependency audit passed.", flush=True)
print("Repository retained for example verification:", repository, flush=True)
