#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Read-only, bounded database and per-Pod actuator sampling for capacity tests."""
import concurrent.futures
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import ssl
import threading
import time
import urllib.request
import urllib.error
import psycopg
from psycopg.rows import dict_row

LATEST = {'sampled_at': 0, 'success': False, 'metrics': [], 'error': 'not sampled'}
HISTORY = deque(maxlen=480)  # Two hours of bounded in-cluster retention across laptop outages.
LOCK = threading.Lock()
PORTS = {'agent-harness-runtime': 8082, 'agent-service': 8084, 'tool-worker': 8085}
ACTUATOR = ('hikaricp.connections.active', 'hikaricp.connections.pending', 'hikaricp.connections.max',
            'hikaricp.connections.timeout', 'hikaricp.connections.acquire', 'hikaricp.connections.usage', 'jvm.memory.used',
            'jvm.gc.pause', 'jvm.threads.live', 'jvm.threads.peak', 'process.cpu.usage', 'http.server.requests',
            *('managed.agents.object.store.'+key for key in ['cache.bytes','cache.entries','cache.hits','active','waiting','inflight.bytes','capacity.timeouts','acquire','requests','bytes','failures']),
            *('managed.agents.runtime.boundary.'+key for key in ['checkpoint_prepare','receipt_lookup','checkpoint_verify','model_prepare','recovery_stage','projection_stage','metadata_transaction']),
            'managed.agents.runtime.inline.read_capacity_yields',
            'managed.agents.runtime.commits', 'managed.agents.runtime.commit.events',
            *('managed.agents.projection.'+key for key in ['active','retries','publication.batch','delivery','wakeup.dropped']),
            *('managed.agents.loadtest.model.'+key for key in ['requests','active','completed','cancelled','errors','chunks']))


def metric(name, value, **labels):
    return {'name': name, 'value': float(value or 0), 'labels': labels}


def prometheus(snapshot):
    values = list(snapshot['metrics']) + [metric('ma_observer_scrape_success', int(snapshot['success'])),
                                         metric('ma_observer_sample_timestamp_seconds', snapshot['sampled_at'])]
    lines = []
    for row in values:
        labels = ','.join(key+'='+json.dumps(str(value)) for key, value in sorted(row['labels'].items()))
        lines.append(row['name'] + ('{' + labels + '}' if labels else '') + ' ' + str(row['value']))
    return '\n'.join(lines) + '\n'


def kube(path):
    service = Path('/var/run/secrets/kubernetes.io/serviceaccount')
    context = ssl.create_default_context(cafile=str(service/'ca.crt'))
    request = urllib.request.Request('https://kubernetes.default.svc'+path,
                                    headers={'Authorization': 'Bearer '+(service/'token').read_text()})
    with urllib.request.urlopen(request, context=context, timeout=5) as response:
        return json.load(response)


def pod_metrics(pod):
    rows = []
    meta, spec, status = pod['metadata'], pod['spec'], pod.get('status', {})
    role = meta.get('labels', {}).get('app.kubernetes.io/component', '')
    name = meta['name']
    rows.append(metric('ma_pod_ready',int(any(c.get('type')=='Ready' and c.get('status')=='True'
        for c in status.get('conditions',[]))),pod=name,service=role))
    for container in status.get('containerStatuses', []):
        rows.append(metric('ma_pod_restarts', container['restartCount'], pod=name, service=role))
    if role not in PORTS or not status.get('podIP') or meta.get('deletionTimestamp'):
        return rows
    headers = {'X-Managed-Agents-Internal-Token': os.environ['INTERNAL_TOKEN']}
    for key in ACTUATOR:
        if key.startswith('managed.agents.projection.') and role!='agent-service' and not (
                key=='managed.agents.projection.publication.batch' and role=='agent-harness-runtime'):
            continue
        if key.startswith('managed.agents.loadtest.model.') and role!='agent-harness-runtime':
            continue
        try:
            request = urllib.request.Request(f"http://{status['podIP']}:{PORTS[role]}/actuator/metrics/{key}", headers=headers)
            with urllib.request.urlopen(request, timeout=2) as response:
                result = json.load(response)
            for measure in result['measurements']:
                rows.append(metric('ma_actuator_'+key.replace('.', '_'), measure['value'],
                                   service=role, pod=name, statistic=measure['statistic']))
        except (urllib.error.URLError, TimeoutError):
            rows.append(metric('ma_actuator_metric_available', 0, service=role, pod=name, metric=key))
    if role == 'agent-service':
        for source in ('backend', 'local'):
            try:
                request = urllib.request.Request(
                    f"http://{status['podIP']}:{PORTS[role]}/actuator/metrics/managed.agents.projection.resource.read?tag=source:{source}",
                    headers=headers)
                with urllib.request.urlopen(request, timeout=2) as response:
                    result = json.load(response)
                for measure in result['measurements']:
                    rows.append(metric('ma_projection_protocol_resource_reads', measure['value'],
                                       service=role, pod=name, source=source,
                                       statistic=measure['statistic']))
            except (urllib.error.URLError, TimeoutError):
                pass

    return rows


def quantity(value):
    for suffix, factor in [('Ki', 1024), ('Mi', 1024**2), ('Gi', 1024**3), ('n', 1e-9), ('u', 1e-6), ('m', 1e-3)]:
        if value.endswith(suffix):
            return float(value[:-len(suffix)])*factor
    return float(value)


def collect():
    metrics = []
    agents = [v for v in os.environ.get('AGENT_IDS', '').split(',') if v]
    since = int(os.environ.get('SINCE_MS', '0'))
    lightweight = os.environ.get('LIGHTWEIGHT_SAMPLING', 'false').lower() == 'true'
    with psycopg.connect(os.environ['DATABASE_URI'], autocommit=True, row_factory=dict_row,
                         connect_timeout=5, options='-c default_transaction_read_only=on -c statement_timeout=3000') as db:
        # The observer never alters platform facts, acquires execution leases or polls individual Sessions.
        filters = [
            ('all', '', ()),
            ('test', "WHERE EXISTS (SELECT 1 FROM ma.protocol_creation p WHERE p.scope_key=s.scope_key "
                     "AND p.receipt::jsonb->'agent'->>'id'=ANY(%s)) AND t.created_at >= %s", (agents, since))]
        if lightweight:
            filters = [('all','',()),('test','WHERE t.created_at >= %s',(since,))]
        for scope, filter_sql, args in filters:
            for row in db.execute('SELECT t.status,count(*) AS count,count(DISTINCT t.scope_key) AS sessions,'
                                  'greatest(0,extract(epoch FROM clock_timestamp())-min(t.created_at)/1000.0) AS oldest '
                                  'FROM ma.turn t JOIN ma.session s USING(scope_key) '+filter_sql+' GROUP BY t.status', args):
                metrics += [metric('ma_turns', row['count'], scope=scope, status=row['status']),
                            metric('ma_turn_oldest_seconds', row['oldest'], scope=scope, status=row['status'])]
            active = db.execute('SELECT count(DISTINCT t.scope_key) AS count,count(*) AS turns FROM ma.turn t '
                                'JOIN ma.session s USING(scope_key) '+filter_sql+
                                (' AND ' if filter_sql else ' WHERE ')+"t.status NOT IN ('succeeded','failed','cancelled')", args).fetchone()
            metrics.append(metric('ma_sessions_active', active['count'], scope=scope))
            metrics.append(metric('ma_turns_active', active['turns'], scope=scope))
        if lightweight:
            lite_topics = [r['scope_key'] for r in db.execute(
                "SELECT scope_key,min(created_at) AS oldest FROM ma.turn WHERE created_at>=%s "
                "AND status NOT IN ('succeeded','failed','cancelled') GROUP BY scope_key ORDER BY oldest LIMIT 3",(since,))]
        else:
            lite_topics = [r['scope_key'] for r in db.execute(
                "SELECT t.scope_key,min(t.created_at) AS oldest FROM ma.turn t WHERE "
                "t.status NOT IN ('succeeded','failed','cancelled') AND t.created_at >= %s AND EXISTS ("
                "SELECT 1 FROM ma.protocol_creation p WHERE p.scope_key=t.scope_key AND "
                "p.receipt::jsonb->'agent'->>'id'=ANY(%s)) GROUP BY t.scope_key ORDER BY oldest LIMIT 3",
                (since, agents))]
        latency_sql = """
            WITH selected AS MATERIALIZED (
              SELECT t.scope_key,t.turn_id,t.created_at FROM ma.turn t
              WHERE t.created_at >= %s AND EXISTS (
                SELECT 1 FROM ma.protocol_creation p WHERE p.scope_key=t.scope_key
                AND p.receipt::jsonb->'agent'->>'id'=ANY(%s))
              ORDER BY t.created_at DESC LIMIT 300),
            times AS (
              SELECT t.scope_key,t.turn_id,t.created_at,
                min(e.created_at) FILTER (WHERE e.type='turn.running') AS started,
                max(e.created_at) FILTER (WHERE e.type='turn.completed') AS completed
              FROM selected t LEFT JOIN ma.public_event e ON e.scope_key=t.scope_key
                AND e.payload::jsonb->>'turn_id'=t.turn_id
                AND e.type IN ('turn.running','turn.completed')
              GROUP BY t.scope_key,t.turn_id,t.created_at)
            SELECT 'admission_queue' AS phase,count(started) AS count,
              percentile_cont(ARRAY[0.5,0.95,0.99]) WITHIN GROUP (ORDER BY (started-created_at)/1000.0) AS quantiles
              FROM times
            UNION ALL SELECT 'end_to_end',count(completed),
              percentile_cont(ARRAY[0.5,0.95,0.99]) WITHIN GROUP (ORDER BY (completed-created_at)/1000.0) FROM times
        """
        if not lightweight:
            for row in db.execute(latency_sql, (max(since, int(time.time()*1000)-600000), agents)):
                metrics.append(metric('ma_latency_samples', row['count'], phase=row['phase']))
                for quantile, value in zip(['0.5', '0.95', '0.99'], row['quantiles'] or []):
                    if value is not None:
                        metrics.append(metric('ma_latency_seconds', value, phase=row['phase'], quantile=quantile))
        for table, condition in [('turn', "delivery_status='pending'"), ('turn_resume', "delivery_status='pending'"),
                                 ('tool_command_outbox', 'broker_id IS NULL'),
                                 ('hands_tool_result_outbox', 'committed_at IS NULL')]:
            row = db.execute('SELECT count(*) AS count FROM ma.'+table+' WHERE '+condition).fetchone()
            metrics.append(metric('ma_outbox_pending', row['count'], queue=table))
        for row in db.execute('SELECT state,count(*) AS count FROM ma.session_queue GROUP BY state'):
            metrics.append(metric('ma_session_queue_entries',row['count'],state=row['state']))
        row=db.execute('SELECT count(*) AS count FROM ma.session_dispatch WHERE notification_pending=TRUE').fetchone()
        metrics.append(metric('ma_session_ready_unpublished',row['count']))
        row=db.execute('SELECT count(*) AS count FROM ma.session_dispatch WHERE next_check_at<=%s AND claim_until<=%s',
                       (int(time.time()*1000),int(time.time()*1000))).fetchone()
        metrics.append(metric('ma_session_dispatch_due',row['count']))
        row=db.execute("SELECT count(*) AS pending,greatest(coalesce(max(extract(epoch FROM clock_timestamp())*1000-next_attempt_at),0),0)/1000 AS oldest_due_seconds "
                       "FROM ma.runtime_commit WHERE publication_state IN ('pending','inflight')").fetchone()
        for name,value in row.items():metrics.append(metric('ma_projection_outbox_'+name,value))
        # Consumer visibility belongs to the query backend; never infer it from broker acceptance.
        for row in db.execute('SELECT status,count(*) AS count FROM ma.hands_execution GROUP BY status'):
            metrics.append(metric('ma_tools', row['count'], status=row['status']))
        for row in db.execute("SELECT state,coalesce(wait_event_type,'none') AS wait,count(*) AS count FROM pg_stat_activity WHERE datname=current_database() GROUP BY state,wait_event_type"):
            metrics.append(metric('ma_pg_connections', row['count'], state=row['state'] or 'unknown', wait=row['wait']))
        for row in db.execute("SELECT usename,state,count(*) AS count,"
                              "max(extract(epoch FROM clock_timestamp()-xact_start)) AS oldest,"
                              "percentile_cont(.95) WITHIN GROUP (ORDER BY extract(epoch FROM clock_timestamp()-xact_start)) AS p95 "
                              "FROM pg_stat_activity WHERE datname=current_database() AND xact_start IS NOT NULL "
                              "AND pid<>pg_backend_pid() GROUP BY usename,state"):
            for quantile, key in [('0.95', 'p95'), ('max', 'oldest')]:
                metrics.append(metric('ma_pg_transaction_age_seconds', row[key],
                                      role=row['usename'] or 'system', state=row['state'] or 'unknown', quantile=quantile))
        counters = db.execute("SELECT deadlocks,xact_commit,xact_rollback,tup_inserted,tup_updated,tup_deleted,"
                              "blks_read,blks_hit,temp_bytes,blk_write_time FROM pg_stat_database WHERE datname=current_database()").fetchone()
        for name, value in counters.items():
            metrics.append(metric('ma_pg_'+name+'_total', value))
        for row in db.execute("SELECT relname,n_tup_ins,n_tup_upd,n_tup_del,n_dead_tup FROM pg_stat_user_tables WHERE schemaname='ma'"):
            for name in ['n_tup_ins','n_tup_upd','n_tup_del','n_dead_tup']:
                metrics.append(metric('ma_pg_table_'+name,row[name],table=row['relname']))
        for name,value in db.execute('SELECT wal_bytes,wal_records,wal_fpi FROM pg_stat_wal').fetchone().items():
            metrics.append(metric('ma_pg_'+name+'_total',value))
        for row in db.execute("SELECT coalesce(wait_event,'none') AS event,count(*) AS count FROM pg_stat_activity "
                              "WHERE datname=current_database() AND wait_event IS NOT NULL GROUP BY wait_event"):
            metrics.append(metric('ma_pg_waiters',row['count'],event=row['event']))
        locks = db.execute("SELECT count(*) AS waiting,coalesce(max(extract(epoch FROM clock_timestamp()-query_start)),0) AS oldest FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'").fetchone()
        metrics += [metric('ma_pg_lock_waiting', locks['waiting']), metric('ma_pg_lock_oldest_seconds', locks['oldest'])]
    namespace = os.environ.get('NAMESPACE', 'agencore-ma')
    pods = kube('/api/v1/namespaces/'+namespace+'/pods')['items']
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for rows in pool.map(pod_metrics, pods):
            metrics.extend(rows)
    for pod in kube('/apis/metrics.k8s.io/v1beta1/namespaces/'+namespace+'/pods')['items']:
        for container in pod['containers']:
            for key, value in container['usage'].items():
                metrics.append(metric('ma_pod_'+key, quantity(value), pod=pod['metadata']['name'], container=container['name']))
    return {'sampled_at': time.time(), 'success': True, 'metrics': metrics, 'active_lite_topics': lite_topics, 'lightweight': lightweight}


def sample_forever():
    global LATEST
    while True:
        started = time.monotonic()
        try:
            snapshot = collect()
        except Exception as error:
            # Do not include connection strings or authentication headers in logs.
            snapshot = {'sampled_at': time.time(), 'success': False, 'metrics': [], 'error': type(error).__name__}
        with LOCK:
            LATEST = snapshot
            HISTORY.append(snapshot)
        print(json.dumps({'sampled_at': snapshot['sampled_at'], 'success': snapshot['success'],
                          'samples': len(snapshot['metrics']), 'error': snapshot.get('error')}), flush=True)
        time.sleep(max(1, 15-(time.monotonic()-started)))


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        with LOCK:
            snapshot = LATEST
            history = list(HISTORY) if self.path == '/history' else None
        if self.path == '/snapshot':
            body, content_type = json.dumps(snapshot).encode(), 'application/json'
        elif self.path == '/history':
            body, content_type = json.dumps(history).encode(), 'application/json'
        elif self.path == '/metrics':
            body, content_type = prometheus(snapshot).encode(), 'text/plain; version=0.0.4'
        elif self.path == '/healthz':
            self.send_response(200 if snapshot['success'] and time.time()-snapshot['sampled_at'] < 60 else 503)
            self.end_headers()
            return
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


if __name__ == '__main__':
    threading.Thread(target=sample_forever, daemon=True).start()
    ThreadingHTTPServer(('0.0.0.0', 9108), Handler).serve_forever()
