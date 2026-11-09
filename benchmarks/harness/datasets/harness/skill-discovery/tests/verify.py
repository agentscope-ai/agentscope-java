import json, hashlib
from pathlib import Path
root=Path('/workspace')
checks={}
for i,c in enumerate(json.loads(Path('/tests/checks.json').read_text())):
    p=root/c['path']
    try:
        if c['kind']=='text': ok=p.read_text().strip()==c['value']
        elif c['kind']=='json': ok=json.loads(p.read_text())==c['value']
        elif c['kind']=='absent': ok=not p.exists()
        elif c['kind']=='sha256': ok=hashlib.sha256(p.read_bytes()).hexdigest()==c['value']
        else: raise ValueError('Unknown verifier check')
    except (FileNotFoundError,UnicodeDecodeError,json.JSONDecodeError): ok=False
    checks[str(i)+':'+c['path']]=ok
Path('/logs/verifier').mkdir(parents=True,exist_ok=True)
Path('/logs/verifier/checks.json').write_text(json.dumps(checks))
Path('/logs/verifier/reward.txt').write_text(str(int(all(checks.values()))))
