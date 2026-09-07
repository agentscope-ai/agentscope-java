"""Local integration probe. Credentials stay in memory; fixtures are isolated Issues."""
import datetime
import json
import os
from pathlib import Path
import urllib.error
import urllib.request

BASE = os.environ.get('ISSUE_TEST_URL', 'http://127.0.0.1:18080')
REPORT = Path(__file__).parent
EVIDENCE = REPORT / 'evidence'
TOKEN = None


def request(method, path, data=None):
    global TOKEN
    if TOKEN is None:
        login = urllib.request.Request(BASE + '/api/auth/login', json.dumps({
            'username': os.environ.get('ISSUE_TEST_USERNAME', 'admin'),
            'password': os.environ.get('ISSUE_TEST_PASSWORD', 'admin'),
        }).encode(), {'Content-Type': 'application/json'})
        TOKEN = json.load(urllib.request.urlopen(login, timeout=15))['token']
    req = urllib.request.Request(BASE + path, None if data is None else json.dumps(data).encode(),
        {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN}, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    raw = response.read()
    value = json.loads(raw) if raw and 'application/json' in response.headers.get('Content-Type', '') else None
    with (EVIDENCE / 'http.jsonl').open('a') as log:
        log.write(json.dumps({'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'method': method,
            'path': path, 'request': data, 'status': response.status,
            'response': value if value is not None else {'bytes': len(raw)}}, ensure_ascii=False) + '\n')
    return response.status, value, raw


def issue(issue_id):
    status, value, _ = request('GET', '/api/v1/issues/' + issue_id)
    assert status == 200, value
    return value['issue']


def save(name, value):
    (EVIDENCE / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    import sys
    if sys.argv[1] == 'setup-dispatch':
        fixtures = {}
        for kind in ['agent', 'team']:
            status, value, _ = request('POST', '/api/v1/issues', {
                'tenant': 'default', 'namespace': 'default',
                'title': '[UI verification] Sidebar ' + kind + ' assignment 20260907',
                'description': '这是 Issue 详情页分配能力的最小验证。请直接返回 SIDEBAR_' + kind.upper() + '_OK 作为最终结果并完成本次执行。不需要联网、访问工作区、修改文件或委派子任务。',
                'priority': 'normal',
            })
            assert status == 201, value
            fixtures[kind] = value['issue']['id']
        save('dispatch-fixtures', fixtures)
        print(json.dumps(fixtures))
    elif sys.argv[1] == 'snapshot':
        fixtures = json.loads((EVIDENCE / 'dispatch-fixtures.json').read_text())
        for kind, identifier in fixtures.items():
            current = issue(identifier)
            status, tasks, _ = request('GET', '/api/v1/agent-tasks?tenant=default&namespace=default&issueId=' + identifier)
            status, comments, _ = request('GET', '/api/v1/issues/' + identifier + '/comments')
            save(kind + '-current', {'issue': current, 'tasks': tasks, 'comments': comments})
            print(kind, identifier, current['status'], [(t['id'], t['status']) for t in tasks['items']])
