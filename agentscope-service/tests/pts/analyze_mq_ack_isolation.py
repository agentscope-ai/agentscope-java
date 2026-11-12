#!/usr/bin/env python3
"""Analyze two-consumer isolated probe artifacts; require complete, unique delivery and ACK."""
import json,sys,statistics,math,re
from pathlib import Path
if len(sys.argv) != 2:
 raise SystemExit("Usage: analyze_mq_ack_isolation.py ARTIFACT_DIRECTORY")
out=Path(sys.argv[1])
if not out.is_dir():
 raise SystemExit("Artifact directory does not exist")
all_results={}
def stats(xs):
 xs=sorted(xs)
 return {'n':len(xs),'p50':xs[math.ceil(len(xs)*.5)-1],'p95':xs[math.ceil(len(xs)*.95)-1],'p99':xs[math.ceil(len(xs)*.99)-1],'max':xs[-1]} if xs else {}
for folder in out.iterdir():
 if not (folder/'metadata.json').exists() or not json.loads((folder/'metadata.json').read_text()).get('completed'):continue
 meta=json.loads((folder/'metadata.json').read_text());events=[];snap=[];acks={};publications={}
 for p in folder.glob('*-events.jsonl'):
  events.extend(json.loads(l) for l in p.read_text().splitlines() if l)
 for p in folder.glob('*-publications.jsonl'):
  for line in p.read_text().splitlines():
   row=json.loads(line);publications[row['id']]=row
 for p in folder.glob('mq-ack-*-?.log'):
  for line in p.read_text().splitlines():
   if 'managed_runtime {' not in line:continue
   try:r=json.loads(line.split('managed_runtime ',1)[1])
   except Exception:continue
   if r.get('event')=='mq.metrics':snap.append(r['values'])
   if r.get('event')=='mq.acked':
    from datetime import datetime
    acks[r['broker_message_id']]=datetime.fromisoformat(r['timestamp'].replace('Z','+00:00')).timestamp()*1000
 samples=[json.loads(l) for l in (folder/'samples.jsonl').read_text().splitlines() if l]
 results={'meta':meta,'unique_completed':len({e['id'] for e in events}),'event_count':len(events)}
 for key,a,b in [('born_to_delivery','born','delivery'),('delivery_to_receive','delivery','received'),('receive_to_final_callback','received','entry'),('callback_execution','entry','finished'),('born_to_completion','born','finished')]:
  results[key+'_seconds']=stats([(e[b]-e[a])/1000 for e in events if e[a]>0 and e[b]>0])
 if events:
  elapsed=(max(e['finished'] for e in events)-min(e['born'] for e in events))/1000
  results['drain_seconds']=elapsed;results['drain_rate']=len(events)/elapsed
 results['per_consumer_peaks']={key:max((s.get(key,0) for s in snap),default=0) for key in ['cache.messages','cache.full_queues','cache.bytes','callback.active','callback.threads_active','callback.queued','receive.inflight','ack.inflight','receive.batch_limit','receive.requested_batch','process_queues']}
 sdk='\n'.join(p.read_text() for p in folder.glob('*-sdk.log'))
 counts=[int(v) for v in re.findall(r'Unacked count: (\d+)',sdk)]
 results['sdk_rejections']={'unacked':len(counts),'unacked_count_min':min(counts,default=0),'unacked_count_max':max(counts,default=0),'send_rate_limit':len(re.findall('send topic .* flow control',sdk)),'cache_full_warnings':sdk.count('Process queue cache is full'),'ordinary_receive_failures':sdk.count('Exception raised during message reception')}
 results['cache_full_samples']=sum(1 for s in snap if s.get('cache.full_queues',0)>0)
 results['log_sample_count']=len(snap)
 finals=[json.loads(p.read_text()) for p in folder.glob('*-final.json')]
 results['final_counters']={key:sum(v['metrics'].get(key,0) for v in finals) for key in ['receive.messages_total','ack.success_total','ack.failures_total','receive.failures_total','callback.success_total','callback.suspend_total']}
 results['final_counters'].update({key:sum(v.get(key,0) for v in finals) for key in ['sent','completed','errors']})
 results['last_status']=samples[-1] if samples else None
 results['publication_confirm_seconds']=stats([(p['confirmed']-p['start'])/1000 for p in publications.values()])
 results['sampled_receive_to_ack_seconds']=stats([(acks[e['id']]-e['received'])/1000 for e in events if e['id'] in acks])
 results['negative_born_delivery_count']=sum(e['delivery']<e['born'] for e in events)
 results['validation']={'unique_publications':len(publications),'all_publications_delivered':set(publications)=={e['id'] for e in events},'unique_events':len(events)==len({e['id'] for e in events}),'all_acked':results['final_counters']['ack.success_total']==meta['messages']}
 assert results['validation']['unique_publications']==meta['messages'] and all(v for k,v in results['validation'].items() if k!='unique_publications'),results['validation']
 results['slowest']=sorted(events,key=lambda e:e['delivery']-e['born'],reverse=True)[:5]
 (folder/'analysis.json').write_text(json.dumps(results,indent=2));all_results[folder.name]=results
(out/'comparison-summary.json').write_text(json.dumps(all_results,indent=2))
for name,r in all_results.items():print(name,json.dumps({k:v for k,v in r.items() if k not in ['last_status','slowest','meta']}))
