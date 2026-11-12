# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Prepare verified executable JAR layers for an existing pinned runtime image.

Do not choose a JAR by its filename alone: Tool Worker publishes a library and
an executable classifier. Image publication must also preserve the base image's
architecture, OS and runtime config, and record the final registry digest.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
import zipfile


COMPONENTS = {'agent-service': ('agent-service', 'io.agentcore.managed.server.agentservice.AgentServiceApp'),
              'agent-harness-runtime': ('agent-harness-runtime/java/brain-agentscope', 'io.agentcore.managed.server.AgentHarnessRuntimeApp'),
              'tool-worker': ('tool-worker', 'io.agentcore.managed.server.worker.ToolWorkerApp'),
              'gateway': ('gateway', 'io.agentcore.managed.server.GatewayApp')}


def executable_jar(directory, main):
    matches = []
    for path in directory.glob('*.jar'):
        with zipfile.ZipFile(path) as jar:
            manifest = jar.read('META-INF/MANIFEST.MF').decode()
            fields = dict(line.split(': ', 1) for line in manifest.splitlines() if ': ' in line)
            if fields.get('Main-Class') == 'org.springframework.boot.loader.launch.JarLauncher' and fields.get('Start-Class') == main:
                if 'BOOT-INF/classes/' + main.replace('.', '/') + '.class' not in jar.namelist():
                    raise ValueError('Executable entry point is missing: ' + str(path))
                matches.append(path)
    if len(matches) != 1:
        raise ValueError('Expected exactly one executable artifact: ' + str(directory))
    return matches[0]


def main(args):
    root = Path(__file__).resolve().parents[3]
    base = json.loads(Path(args.base_manifest).read_text())
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    artifacts = {}
    for component, (module, entrypoint) in COMPONENTS.items():
        path = executable_jar(root / 'managed-agents' / module / 'target', entrypoint)
        image = base['agencore-ma/' + component]['image']
        if '@sha256:' not in image:
            raise ValueError('Base image is not pinned')
        layer = output / (component + '.tar')
        with tarfile.open(layer, 'w') as tar:
            tar.add(path, arcname='app.jar')
            tar.add(root / 'managed-agents/deploy/profiles/qwen38-eval.json', arcname='etc/managed-agents/qwen38-eval.json')
        artifacts[component] = {'base': image, 'layer': str(layer.resolve()),
            'artifact_sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'start_class': entrypoint}
    (output / 'java-artifacts.json').write_text(json.dumps(artifacts, indent=2))
    print('Verified four executable artifacts and prepared image layers')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-manifest', required=True)
    parser.add_argument('--output', required=True)
    main(parser.parse_args())
