# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Derive group summaries from immutable trial outcomes, without changing any score."""
import argparse
from datetime import datetime
import hashlib
import html
import json
from pathlib import Path
from reports.unified import summarize, render


def groups(rows, field):
    result={}
    for key in sorted({str(r.get(field,'unknown')) for r in rows}):
        items=[r for r in rows if str(r.get(field,'unknown'))==key]
        scored=[r for r in items if r.get('scoring_status')=='scored' and r.get('score') is not None]
        passed=[r for r in scored if r['score']==1]
        result[key]={'planned':len(items),'scored':len(scored),'passed':len(passed),
            'failed_score':sum(r['score']==0 for r in scored),
            'adapter_unsupported':sum(r['execution_status']=='adapter_unsupported' for r in items),
            'other_unscored':sum(r not in scored and r['execution_status']!='adapter_unsupported' for r in items),
            'protocol_valid':sum(bool(r.get('protocol_valid')) for r in items),
            'scoring_coverage':len(scored)/len(items),
            'pass_rate_scored':len(passed)/len(scored) if scored else None,
            'pass_rate_planned':len(passed)/len(items)}
    return result


def overview(manifest,summary):
    rows=summary['results']
    return {'run_id':manifest['run_id'],'suite':manifest['suite'],'model':manifest['model'],
        'planned':summary['planned'],'scored':summary['scored'],'passed':summary['passed'],
        'by_agent':groups(rows,'agent'),'by_category':groups(rows,'category'),
        'non_passing':[{'agent':r['agent'],'task':r['task'],'score':r.get('score'),
            'execution_status':r['execution_status'],'scoring_status':r['scoring_status'],
            'error_type':r.get('error_type'),'unscored_reason':r.get('unscored_reason'),
            'unsupported_reason':r.get('unsupported_reason'),'behavior_checks':r.get('behavior_checks'),
            'verifier_checks':r.get('verifier_checks'),'archive_path':r.get('archive_path')} for r in rows if r.get('score')!=1]}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('directory',type=Path)
    args=parser.parse_args();root=args.directory
    manifest=json.loads((root/'manifest.json').read_text());original=json.loads((root/'summary.json').read_text())
    summary=summarize(manifest,original['results']);value=overview(manifest,summary)
    value['source_summary_sha256']=hashlib.sha256((root/'summary.json').read_bytes()).hexdigest()
    (root/'overview.json').write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
    headers=['分组','计划','已评分','通过','得分失败','适配器未覆盖','其他未评分']
    sections=[]
    for title,key in [('按 Agent','by_agent'),('按能力类别','by_category')]:
        table='<h2>'+title+'</h2><table><tr>'+''.join('<th>'+x+'</th>' for x in headers)+'</tr>'
        for name,data in value[key].items():
            table+='<tr>'+''.join('<td>'+html.escape(str(v))+'</td>' for v in [name]+[data[k] for k in ('planned','scored','passed','failed_score','adapter_unsupported','other_unscored')])+'</tr>'
        sections.append(table+'</table>')
    report=render(manifest,summary).replace('<input id="filter"',''.join(sections)+'<input id="filter"',1)
    (root/'report-overview.html').write_text(report)
    print(json.dumps({k:v for k,v in value.items() if k not in ('non_passing','by_category')},ensure_ascii=False,indent=2))

if __name__=='__main__':main()
