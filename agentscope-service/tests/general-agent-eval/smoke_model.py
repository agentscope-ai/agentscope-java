# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Fresh Session model smoke; persists admission identities before waiting."""
import argparse
import json
from pathlib import Path
import uuid
from session_client import SessionClient


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', required=True)
    p.add_argument('--model-profile', required=True)
    p.add_argument('--output', required=True)
    args = p.parse_args()
    config = json.loads(Path(args.config).read_text())
    profile = json.loads(Path(args.model_profile).read_text())
    bindings = profile['brain']['model']['bindings']
    if len(bindings) != 1:
        raise ValueError('Expected one evaluation binding')
    model = next(iter(bindings))
    output = Path(args.output)
    output.mkdir(exist_ok=False, parents=True)
    (output/'profile.json').write_text(json.dumps(profile, indent=2))
    for target in config['targets']:
        client = SessionClient(target.get('base_url', target['cluster_url']), target['headers'])
        env = next(x['id'] for x in client.api('/api/environments') if x['name'] == 'general-agent-eval-20261030')
        definition = {'name': 'Unified real model smoke', 'system': 'Follow the user request precisely.',
                      'model': model, 'maxIters': 3, 'defaultEnvironmentId': env,
                      'tools': [{'type': 'agent_toolset', 'defaultConfig': {'enabled': False}}]}
        agent = client.api('/api/v1/agents', {'agentKey': 'model-smoke-'+uuid.uuid4().hex[:12],
                            'displayName': definition['name'], 'binding': {'kind': 'managed'}, 'definition': definition})['agent']['id']
        created = client.create(agent, env, {'evaluation': 'qwen38-model-smoke'}, str(uuid.uuid4()))
        session = created['id']
        turn = client.send(session, 'Reply exactly EVAL_READY', str(uuid.uuid4()))
        row = {'target': target['name'], 'agent': agent, 'session': session, 'turn': turn, 'status': 'running'}
        path = output/(target['name']+'.json')
        path.write_text(json.dumps(row, indent=2))
        try:
            state, events = client.wait(session, turn, timeout=240)
            row.update(status=state['status'], text=client.text(events), events=events)
            row['passed'] = state['status'] == 'completed' and client.text(events).strip() == 'EVAL_READY'
        except Exception as error:
            row.update(status='infrastructure_error', error=type(error).__name__)
        path.write_text(json.dumps(row, ensure_ascii=False, indent=2))
        print(target['name'], row['status'], row.get('passed', False), flush=True)


if __name__ == '__main__':
    main()
