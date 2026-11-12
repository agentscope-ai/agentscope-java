# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Declared package-distribution routing; repository signature checks stay enabled."""
import json
import re
import shlex


def apt_mirror_text(text):
    mirrors = {
        'deb.debian.org/debian-security': 'mirrors.aliyun.com/debian-security',
        'deb.debian.org/debian': 'mirrors.aliyun.com/debian',
        'security.debian.org/debian-security': 'mirrors.aliyun.com/debian-security',
        'archive.ubuntu.com/ubuntu': 'mirrors.aliyun.com/ubuntu',
        'security.ubuntu.com/ubuntu': 'mirrors.aliyun.com/ubuntu',
    }
    for source, target in mirrors.items():
        text = re.sub(r'https?://' + re.escape(source) + r'(?=[/\s]|$)',
                      'https://' + target, text)
    return text


async def prepare_network(environment, profile):
    if profile is None:
        return {'profile': None, 'files': {}}
    if profile not in {'aliyun-apt-v1', 'aliyun-apt-pypi-v1', 'aliyun-apt-pypi-planarity06-v1',
                       'aliyun-apt-pypi-debian-proxy-v1'}:
        raise ValueError('Unknown declared environment network profile')
    # The source is controller code, never a task/model-provided shell program.
    import inspect
    script = ('import re,json,hashlib\nfrom pathlib import Path\n' +
              inspect.getsource(apt_mirror_text) + '''
files = {}
paths = [Path('/etc/apt/sources.list'),
         *Path('/etc/apt/sources.list.d').glob('*.list'),
         *Path('/etc/apt/sources.list.d').glob('*.sources')]
for path in paths:
    if not path.is_file():
        continue
    before = path.read_text()
    after = apt_mirror_text(before)
    if before != after:
        path.write_text(after)
    files[str(path)] = {'before': hashlib.sha256(before.encode()).hexdigest(),
                       'after': hashlib.sha256(after.encode()).hexdigest()}
print(json.dumps(files))
''')
    result = await environment.exec('python3 -c ' + shlex.quote(script), timeout_sec=30)
    if result.return_code != 0:
        raise RuntimeError('Declared package-distribution preparation failed')
    files = json.loads(result.stdout)
    if profile in {'aliyun-apt-pypi-v1', 'aliyun-apt-pypi-planarity06-v1',
                   'aliyun-apt-pypi-debian-proxy-v1'}:
        # Configure distribution routing before admission, equally in every lane.
        # Keep TLS and package-hash verification enabled; package pins stay in the task.
        script = '''import configparser,hashlib,json
from pathlib import Path
p=Path('/etc/pip.conf')
before=p.read_bytes() if p.exists() else b''
c=configparser.ConfigParser()
c.read_string(before.decode())
if not c.has_section('global'): c.add_section('global')
c.set('global','index-url','https://mirrors.aliyun.com/pypi/simple/')
with p.open('w') as f: c.write(f)
print(json.dumps({'before':hashlib.sha256(before).hexdigest(),
                  'after':hashlib.sha256(p.read_bytes()).hexdigest()}))
'''
        if profile == 'aliyun-apt-pypi-planarity06-v1':
            # The package's pre-2025-release API is required by the upstream reference
            # solution; planarity 1.0.0 dropped graph attributes used by pyknotid.
            script = script.replace("with p.open('w') as f: c.write(f)", '''
constraint=Path('/etc/managed-eval-constraints.txt')
assert not c.has_option('install','constraint')
assert not c.has_option('global','constraint') or c.get('global','constraint')==str(constraint)
constraint.write_text('planarity==0.6\\n')
c.set('global','constraint',str(constraint))
with p.open('w') as f: c.write(f)
''')
        result = await environment.exec('python3 -c ' + shlex.quote(script), timeout_sec=30)
        if result.return_code != 0:
            raise RuntimeError('Declared Python package-distribution preparation failed')
        files['/etc/pip.conf'] = json.loads(result.stdout)
    if profile == 'aliyun-apt-pypi-debian-proxy-v1':
        # Some original scripts add deb.debian.org sources after preparation.
        # Route only that public HTTP host through the declared HTTPS mirror;
        # APT still verifies the upstream signed Release and package hashes.
        script = '''import hashlib,json
from pathlib import Path
p=Path('/etc/apt/apt.conf.d/99managed-eval-debian-proxy')
before=p.read_bytes() if p.exists() else b''
assert not before
value=b'Acquire::http::Proxy::deb.debian.org "http://benchmark-debian-distribution.managed-agents-benchmark.svc.cluster.local:9014";\\n'
p.write_bytes(value)
print(json.dumps({'before':hashlib.sha256(before).hexdigest(),
                  'after':hashlib.sha256(value).hexdigest()}))
'''
        result = await environment.exec('python3 -c ' + shlex.quote(script), timeout_sec=30)
        if result.return_code != 0:
            raise RuntimeError('Declared Debian distribution proxy preparation failed')
        files['/etc/apt/apt.conf.d/99managed-eval-debian-proxy'] = json.loads(result.stdout)
    return {'profile': profile, 'files': files,
            'repository_signature_verification': 'unchanged'}


async def prepare_runtime(environment, profile):
    """Supply the declared stdlib interpreter required by the terminal transport.

    Some official Ubuntu task images contain curl but no Python. Install the same
    digest-checked runtime before model admission in each lane; no task answers or
    verifier files are involved. An unknown/failed mutation is never replayed.
    """
    if profile is None:
        return {'profile': None}
    if profile == 'python3139-bootstrap-ca-curl-v2':
        # These minimal upstream images have neither Python/curl nor CA roots.
        # APT still authenticates its signed Release/Packages chain over HTTP;
        # restore HTTPS immediately after installing the distro trust store.
        command = ("set -euo pipefail; test ! -x /usr/bin/curl; "
                   "for p in /etc/apt/sources.list /etc/apt/sources.list.d/*.list "
                   "/etc/apt/sources.list.d/*.sources; do test ! -f \"$p\" || "
                   "sed -i -e 's|http://archive.ubuntu.com/ubuntu|http://mirrors.aliyun.com/ubuntu|g' "
                   "-e 's|http://security.ubuntu.com/ubuntu|http://mirrors.aliyun.com/ubuntu|g' \"$p\"; done; "
                   "apt-get update > /tmp/managed-bootstrap-apt.log 2>&1; "
                   "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "
                   "ca-certificates curl >> /tmp/managed-bootstrap-apt.log 2>&1; "
                   "for p in /etc/apt/sources.list /etc/apt/sources.list.d/*.list "
                   "/etc/apt/sources.list.d/*.sources; do test ! -f \"$p\" || "
                   "sed -i 's|http://mirrors.aliyun.com/ubuntu|https://mirrors.aliyun.com/ubuntu|g' \"$p\"; done")
        result = await environment.exec(command, timeout_sec=180)
        if result.return_code != 0:
            raise RuntimeError('Signed APT CA/curl bootstrap failed; preserve the task Pod')
        result = await environment.exec("dpkg-query -W -f='${Package}=${Version}\\n' | LC_ALL=C sort | sha256sum", timeout_sec=30)
        if result.return_code != 0 or not re.match(r'^[0-9a-f]{64}\s', result.stdout):
            raise RuntimeError('Bootstrap package inventory hash is unavailable')
        packages_sha = result.stdout.split()[0]
        receipt = await prepare_runtime(environment, 'python3139-standalone-v1')
        return {**receipt, 'profile': profile, 'bootstrap_packages_sha256': packages_sha,
                'bootstrap_distribution': 'signed Ubuntu APT HTTP bootstrap, then verified HTTPS'}
    if profile == 'python3139-bootstrap-curl-v1':
        # Some official Ubuntu images have apt-get but neither curl nor Python.
        # Bootstrap the downloader with normal signed APT, then use the same
        # digest-verified interpreter. Record all package versions for comparison.
        command = ("set -euo pipefail; test ! -x /usr/bin/curl; "
                   "for p in /etc/apt/sources.list /etc/apt/sources.list.d/*.list "
                   "/etc/apt/sources.list.d/*.sources; do test ! -f \"$p\" || "
                   "sed -i -e 's|http://archive.ubuntu.com/ubuntu|https://mirrors.aliyun.com/ubuntu|g' "
                   "-e 's|http://security.ubuntu.com/ubuntu|https://mirrors.aliyun.com/ubuntu|g' \"$p\"; done; "
                   "apt-get update > /tmp/managed-bootstrap-apt.log 2>&1; "
                   "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl "
                   ">> /tmp/managed-bootstrap-apt.log 2>&1")
        result = await environment.exec(command, timeout_sec=180)
        if result.return_code != 0:
            raise RuntimeError('Signed APT curl bootstrap failed; preserve the task Pod')
        result = await environment.exec("dpkg-query -W -f='${Package}=${Version}\\n' | LC_ALL=C sort | sha256sum", timeout_sec=30)
        if result.return_code != 0 or not re.match(r'^[0-9a-f]{64}\s', result.stdout):
            raise RuntimeError('Bootstrap package inventory hash is unavailable')
        packages_sha = result.stdout.split()[0]
        receipt = await prepare_runtime(environment, 'python3139-standalone-v1')
        return {**receipt, 'profile': profile, 'bootstrap_packages_sha256': packages_sha,
                'bootstrap_distribution': 'signed Aliyun Ubuntu APT; curl'}
    if profile in {'swe-requests-httpbin-v1', 'swe-requests-httpbin-py27-v1', 'swe-requests-vendored-ca-v1'}:
        import inspect
        from sandbox_eval.requests_runtime import install_requests_runtime, install_vendored_ca_fixture
        parser = await prepare_runtime(environment, 'swe-harbor-uv0713-v1')
        legacy = profile != 'swe-requests-httpbin-v1'
        script = (inspect.getsource(install_vendored_ca_fixture) + '\n' + inspect.getsource(install_requests_runtime) +
                  '\nimport json;print(json.dumps(install_requests_runtime(' + repr(legacy) + ', ' +
                  repr(profile == 'swe-requests-vendored-ca-v1') + ')))')
        result = await environment.exec('/opt/miniconda3/bin/python -c ' + shlex.quote(script), timeout_sec=240)
        if result.return_code != 0:
            raise RuntimeError('Requests HTTP dependency preparation failed; preserve the task Pod')
        receipt = json.loads(result.stdout)
        if receipt.get('profile') != profile or receipt.get('tls_verification') is not True:
            raise RuntimeError('Requests dependency preparation receipt is incomplete')
        return {**receipt, 'parser': parser}
    if profile == 'swe-harbor-uv0713-v1':
        # Equivalent to the pinned Harbor Dockerfile's uv install and /logs
        # creation. The parser interpreter stays separate from conda/testbed.
        # Neither source files nor official tests are modified here.
        cache = 'http://benchmark-dependency-cache.managed-agents-benchmark.svc.cluster.local:9008/'
        python_archive = 'python/20251014/cpython-3.13.9+20251014-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz'
        python_sha = 'c0ccda275948c79996e46993c2c5476ff5cca606dee530f1dea712179131b348'
        uv_sha = '607aecac8ff87db37ae26978005537d36bd552e1775c69e440e2ab22c2fae315'
        command = ('set -euo pipefail; test ! -e /opt/managed-eval-parser-python; '
                   'test ! -e /root/.local/bin/uv; mkdir -p /root/.local/bin /logs; '
                   'curl -fsS ' + shlex.quote(cache + 'swe/uv-0.7.13-tools.tar.gz') +
                   ' -o /tmp/managed-uv.tar.gz; '
                   "printf '%s\\n' " + shlex.quote(uv_sha + '  /tmp/managed-uv.tar.gz') +
                   ' | sha256sum -c -; tar xzf /tmp/managed-uv.tar.gz -C /root/.local/bin; '
                   'curl -fsS ' + shlex.quote(cache + python_archive) +
                   ' -o /tmp/managed-parser-python.tar.gz; '
                   "printf '%s\\n' " + shlex.quote(python_sha + '  /tmp/managed-parser-python.tar.gz') +
                   ' | sha256sum -c -; mkdir /opt/managed-eval-parser-python; '
                   'tar xzf /tmp/managed-parser-python.tar.gz --strip-components=1 -C /opt/managed-eval-parser-python')
        result = await environment.exec(command, timeout_sec=180)
        if result.return_code != 0:
            raise RuntimeError('SWE uv/parser preparation failed; preserve the task Pod')
        for command, expected in [('/root/.local/bin/uv --version', 'uv 0.7.13'),
                ('/opt/managed-eval-parser-python/bin/python3 --version', 'Python 3.13.9')]:
            result = await environment.exec(command, timeout_sec=30)
            if result.return_code != 0 or result.stdout.strip() != expected:
                raise RuntimeError('SWE uv/parser versions were not verified')
        return {'profile': profile, 'uv_version': '0.7.13', 'uv_archive_sha256': uv_sha,
                'parser_python_version': '3.13.9', 'python_archive_sha256': python_sha,
                'testbed_interpreter': 'unchanged'}
    if profile != 'python3139-standalone-v1':
        raise ValueError('Unknown declared terminal runtime profile')
    url = ('http://benchmark-dependency-cache.managed-agents-benchmark.svc.cluster.local:9008/'
           'python/20251014/cpython-3.13.9+20251014-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz')
    sha = 'c0ccda275948c79996e46993c2c5476ff5cca606dee530f1dea712179131b348'
    command = ('set -euo pipefail; test ! -e /opt/managed-eval-python; '
               'test ! -e /usr/local/bin/python3; test ! -e /usr/local/bin/python; '
               'curl --fail --silent --show-error ' + shlex.quote(url) +
               ' -o /tmp/managed-eval-python.tar.gz; '
               "printf '%s\\n' " + shlex.quote(sha + '  /tmp/managed-eval-python.tar.gz') +
               ' | sha256sum -c -; mkdir /opt/managed-eval-python; '
               'tar xzf /tmp/managed-eval-python.tar.gz --strip-components=1 -C /opt/managed-eval-python; '
               'ln -s /opt/managed-eval-python/bin/python3 /usr/local/bin/python3; '
               'ln -s /opt/managed-eval-python/bin/python3 /usr/local/bin/python; python3 --version')
    result = await environment.exec(command, timeout_sec=120)
    if result.return_code != 0:
        raise RuntimeError('Declared Python runtime failed (exit=' + str(result.return_code) +
                           ', stderr=' + result.stderr[-300:] + '); preserve the task Pod')
    version = await environment.exec('python3 --version', timeout_sec=20)
    if version.return_code != 0 or version.stdout.strip() != 'Python 3.13.9':
        raise RuntimeError('Declared Python version was not verified; preserve the task Pod')
    return {'profile': profile, 'archive_sha256': sha, 'python_version': '3.13.9'}
