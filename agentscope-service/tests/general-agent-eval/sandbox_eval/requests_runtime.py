# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Reproducible HTTP dependency for the older Requests SWE environments.

This prepares an external test service, never a task solution or test. The same
profile must be accepted on a clean reference and used in model and grader Pods.
"""


def install_vendored_ca_fixture(root):
    """Pin only the legacy vendored trust data as a reproducible setup commit.

    Unlike newer Requests, this image ignores certifi. The setup commit is the
    model/clean-grader baseline, so the environmental certificate is never
    smuggled into the agent's prediction patch. Library logic/tests stay intact.
    """
    import hashlib
    import os
    from pathlib import Path
    import subprocess

    repo = Path('/testbed')
    bundle = repo / 'requests/cacert.pem'
    before, fixture = bundle.read_bytes(), (root / 'ca.crt').read_bytes()
    assert hashlib.sha256(before).hexdigest() == '113be89390a3358988484f3e9fa903751abc168f3f4ba9e2ee899fc1b15b8216'
    assert hashlib.sha256(fixture).hexdigest() == 'c742684fb5c93ce55e3b5a03c493ee0cc8d05e5b5440be5e6d19d0a85a700b72'

    def git(*args, **kwargs):
        return subprocess.check_output(['git', '-C', str(repo), *args], **kwargs).decode().strip()

    parent = git('rev-parse', 'HEAD')
    assert parent == '20ea0242e35be03a26f77ac91498655c862d0471'
    assert git('status', '--porcelain') == '', 'Trust preparation requires a pristine task'
    data = before + b'\n' + fixture
    blob = git('hash-object', '-w', '--stdin', input=data)
    env = {**os.environ, 'GIT_INDEX_FILE': str(root / 'trust.index'),
        'GIT_AUTHOR_NAME': 'Managed Eval', 'GIT_AUTHOR_EMAIL': 'evaluation@localhost',
        'GIT_COMMITTER_NAME': 'Managed Eval', 'GIT_COMMITTER_EMAIL': 'evaluation@localhost',
        'GIT_AUTHOR_DATE': '2000-01-01T00:00:00+0000', 'GIT_COMMITTER_DATE': '2000-01-01T00:00:00+0000'}
    git('read-tree', parent, env=env)
    git('update-index', '--cacheinfo', '100644', blob, 'requests/cacert.pem', env=env)
    tree = git('write-tree', env=env)
    commit = git('commit-tree', tree, '-p', parent,
                 input=b'Prepare isolated HTTPBin CA fixture\n', env=env)
    assert commit == 'f662d11c1739a1280ceb5c975b22e6a47e5025c3', 'Unexpected trust setup commit'
    assert git('diff-tree', '--no-commit-id', '--name-only', '-r', commit) == 'requests/cacert.pem'
    git('update-ref', 'HEAD', commit, parent)
    git('read-tree', commit)
    bundle.write_bytes(data)
    assert git('status', '--porcelain') == ''
    return {'setup_commit': commit, 'parent': parent, 'path': 'requests/cacert.pem',
            'before_sha256': hashlib.sha256(before).hexdigest(),
            'after_sha256': hashlib.sha256(data).hexdigest(), 'source_logic_and_tests_modified': False}


def install_requests_runtime(legacy_python=False, vendored_trust=False):
    import hashlib
    import json
    import os
    from pathlib import Path
    import socket
    import subprocess
    import tarfile
    import time
    import urllib.request

    os.umask(0o077)
    profile = 'swe-requests-httpbin-py27-v1' if legacy_python else 'swe-requests-httpbin-v1'
    if vendored_trust:
        assert legacy_python
        profile = 'swe-requests-vendored-ca-v1'
    root = Path('/opt/managed-eval-' + profile.removeprefix('swe-'))
    root.mkdir(exist_ok=False)
    test_python = '/opt/miniconda3/envs/testbed/bin/python'
    service_python = '/opt/miniconda3/bin/python'
    archive_sha = ('30d6162b13be558b37c5db8b18d29adcc2634ba10ea8c4265e5995093c00d926'
                   if legacy_python else 'be6b1e57b6554f9688c3c3dc2db435e52fd56e176673afa09b973a1cde6cfe3e')
    certifi_version = '2021.10.8' if legacy_python else '2025.1.31'

    def save(name, value):
        (root / name).write_text(json.dumps(value, indent=2))

    def run(args, timeout=120):
        with (root / 'setup.log').open('a') as log:
            subprocess.run(args, stdout=log, stderr=log, check=True, timeout=timeout)

    save('intent.json', {'profile': profile, 'archive_sha256': archive_sha,
                        'at': time.time(), 'model_calls': 0})
    try:
        assert subprocess.check_output([service_python, '-c',
            'import sys;print("%s.%s" % sys.version_info[:2])'], text=True).strip() == '3.11'
        test_version = subprocess.check_output([test_python, '-c',
            'import sys;print("%s.%s" % sys.version_info[:2])'], text=True).strip()
        assert test_version == ('2.7' if legacy_python else '3.9'), 'Unexpected testbed interpreter'
        assert socket.gethostbyname('httpbin.org') == '127.0.0.1', 'Exact HTTPBin host alias required'
        for port in (1, 80, 443):
            with socket.socket() as sock:
                sock.settimeout(1)
                assert sock.connect_ex(('127.0.0.1', port)) != 0, 'Unexpected existing listener'
        url = ('http://benchmark-dependency-cache.managed-agents-benchmark.svc.cluster.local:9008/'
               'requests/' + ('httpbin-py27-v1.tar.gz' if legacy_python else 'httpbin-v1.tar.gz'))
        with urllib.request.urlopen(url, timeout=45) as response:
            data = response.read()
        assert hashlib.sha256(data).hexdigest() == archive_sha, 'Dependency archive SHA mismatch'
        archive = root / 'packages.tar.gz'
        archive.write_bytes(data)
        with tarfile.open(archive) as source:
            for member in source.getmembers():
                assert not member.name.startswith('/') and '..' not in Path(member.name).parts
                assert member.isfile() or member.isdir(), 'Unexpected archive link/device'
            source.extractall(root)
        manifest = json.loads((root / 'manifest.json').read_text())
        assert len(manifest) == 17 and len({item['filename'] for item in manifest}) == 17
        for item in manifest:
            assert Path(item['filename']).name == item['filename']
            assert hashlib.sha256((root / 'wheels' / item['filename']).read_bytes()).hexdigest() == item['sha256']
        service = [item for item in manifest if item['name'].lower() != 'certifi']
        certifi = [item for item in manifest if item['name'].lower() == 'certifi']
        assert len(service) == 16 and len(certifi) == 1 and certifi[0]['version'] == certifi_version
        run([service_python, '-m', 'venv', str(root / 'venv')])
        run([str(root / 'venv/bin/python'), '-m', 'pip', 'install', '--no-index', '--no-deps',
             *[str(root / 'wheels' / item['filename']) for item in service]])
        run([str(root / 'venv/bin/python'), '-m', 'pip', 'check'])
        run([test_python, '-m', 'pip', 'install', '--no-index', '--no-deps',
             str(root / 'wheels' / certifi[0]['filename'])])
        if vendored_trust:
            url = ('http://benchmark-dependency-cache.managed-agents-benchmark.svc.cluster.local:9008/'
                   'requests/httpbin-legacy-leaf-v1.tar.gz')
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read()
            assert hashlib.sha256(data).hexdigest() == '35efe2bc439439f78793808141eb5f7e27ecb8f9aea1806cdedcd3537141c3f4'
            leaf_archive = root / 'leaf.tar.gz'
            leaf_archive.write_bytes(data)
            with tarfile.open(leaf_archive) as source:
                assert set(source.getnames()) == {'ca.crt', 'server.crt', 'server.key'}
                assert all(member.isfile() for member in source.getmembers())
                source.extractall(root)
        else:
            run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '7',
             '-keyout', str(root / 'ca.key'), '-out', str(root / 'ca.crt'),
             '-subj', '/CN=Managed Eval isolated HTTPBin CA'])
            run(['openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(root / 'server.key'),
             '-out', str(root / 'server.csr'), '-subj', '/CN=httpbin.org'])
            (root / 'server.ext').write_text('subjectAltName=DNS:httpbin.org\nbasicConstraints=CA:FALSE\n'
            'keyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
            run(['openssl', 'x509', '-req', '-in', str(root / 'server.csr'), '-CA', str(root / 'ca.crt'),
             '-CAkey', str(root / 'ca.key'), '-CAcreateserial', '-out', str(root / 'server.crt'),
             '-days', '7', '-extfile', str(root / 'server.ext')])
        bundle = Path(subprocess.check_output([test_python, '-c',
            'import certifi;print(certifi.where())'], text=True).strip())
        before = bundle.read_bytes()
        (root / 'certifi-before.pem').write_bytes(before)
        bundle.write_bytes(before + b'\n' + (root / 'ca.crt').read_bytes())
        trust_setup = install_vendored_ca_fixture(root) if vendored_trust else None
        processes = []
        for port in (80, 443):
            args = [str(root / 'venv/bin/gunicorn'), '--bind', '127.0.0.1:' + str(port),
                    '--workers', '2', '--threads', '4', '--timeout', '60', 'httpbin:app']
            if port == 443:
                args += ['--certfile', str(root / 'server.crt'), '--keyfile', str(root / 'server.key')]
            with (root / (str(port) + '.log')).open('x') as log:
                child = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                         start_new_session=True)
            processes.append({'port': port, 'pid': child.pid})
            save('services.json', processes)
        for _ in range(40):
            ready = []
            for port in (80, 443):
                with socket.socket() as sock:
                    sock.settimeout(1)
                    ready.append(sock.connect_ex(('127.0.0.1', port)) == 0)
            if all(ready):
                break
            time.sleep(.25)
        assert all(ready), 'HTTPBin did not listen'
        environment = {k: v for k, v in os.environ.items()
                       if k.lower() not in {'http_proxy', 'https_proxy', 'all_proxy', 'httpbin_url'}}
        probe = ('import requests,json;print(json.dumps([requests.get(u, timeout=5).status_code '
                 'for u in ["http://httpbin.org/get", "https://httpbin.org/get"]]))')
        assert json.loads(subprocess.check_output([test_python, '-c', probe], cwd='/testbed',
            env=environment, text=True, timeout=20)) == [200, 200]
        # Only uv's dependency/parser downloads use the GitHub-only proxy. Test
        # processes must retain direct/local HTTP and TLS semantics.
        uv = Path('/root/.local/bin/uv')
        real_uv = uv.with_name('uv-managed-real')
        assert uv.is_file() and not real_uv.exists()
        uv.rename(real_uv)
        uv.write_text('#!/bin/sh\n'
            'export HTTPS_PROXY=http://benchmark-github-egress.managed-agents-benchmark.svc.cluster.local:9009\n'
            'export NO_PROXY=.aliyun.com,pypi.org,files.pythonhosted.org,.cluster.local,localhost,127.0.0.1\n'
            'exec /root/.local/bin/uv-managed-real "$@"\n')
        uv.chmod(0o755)
        receipt = {'profile': profile, 'archive_sha256': archive_sha,
            'packages': manifest, 'httpbin_version': '0.10.4', 'certifi_version': certifi_version,
            'testbed_python_version': test_version,
            'service_interpreter': service_python, 'testbed_interpreter': test_python,
            'tls_verification': True,
            'ca_scope': 'testbed certifi and pinned vendored certificate data' if vendored_trust else 'testbed certifi only',
            'trust_setup': trust_setup,
            'certifi_before_sha256': hashlib.sha256(before).hexdigest(),
            'parser_proxy_scope': 'uv executable only', 'httpbin_alias': '127.0.0.1',
            'official_tests_modified': False, 'at': time.time()}
        save('exit.json', {'completed': True, **receipt})
        return receipt
    except BaseException as error:
        save('exit.json', {'completed': False, 'error': type(error).__name__ + ': ' + str(error),
                          'at': time.time()})
        raise
