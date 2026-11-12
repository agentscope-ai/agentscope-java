#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Plot sanitized active-Turn and per-Pod load trends from an analyzed PTS stage."""
import argparse
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def run(args):
    summary = json.loads((args.stage/'analysis.json').read_text())
    points = summary['load_timeline']
    if not points:
        raise ValueError('No valid load samples to plot')
    origin = points[0]['timestamp']
    xs = [(p['timestamp']-origin)/60 for p in points]
    synthetic = summary.get('synthetic_model',False)
    fig, axes = plt.subplots(4 if synthetic else 3,2,figsize=(12,14 if synthetic else 11),layout='constrained')
    ax = axes[0,0]
    ax.plot(xs,[p['active_turns'] for p in points],label='Active Turns',color='#2563eb')
    if all('active_sessions' in p for p in points):
        ax.plot(xs,[p['active_sessions'] for p in points],label='Active Sessions',linestyle='--',color='#d97706')
    ax.set_title('Nonterminal test work (sampled)')
    ax.set_ylabel('Count')
    ax.legend(loc='upper right',fontsize=8)
    roles = ['agent-harness-runtime','tool-worker']
    names = sorted({name for p in points for name in p['pods'] if name.split('/')[-1] in roles})
    colors = ['#2563eb','#0d9488','#d97706','#be185d','#7c3aed','#475569']
    for index,name in enumerate(names):
        role = 'Brain' if name.endswith('/agent-harness-runtime') else 'Worker'
        label = role+' '+name.split('/')[0].split('-')[-1]
        color = colors[index%len(colors)]
        for ax,key in [(axes[0,1],'cpu_cores'),(axes[1,0],'memory_mib'),
                       (axes[1,1],'jvm_threads_live'),(axes[2,0],'hikaricp_connections_pending'),
                       (axes[2,1],'hikaricp_connections_active')]:
            values = [p['pods'].get(name,{}).get(key,float('nan')) for p in points]
            if any(value==value for value in values):
                ax.plot(xs,values,label=label,color=color,linewidth=1.4)
    for ax,title,ylabel in [(axes[0,1],'Per-Pod CPU','CPU cores'),
                            (axes[1,0],'Per-Pod memory','MiB'),
                            (axes[1,1],'Per-Pod JVM threads (includes idle threads)','Live threads'),
                            (axes[2,0],'Per-Pod DB pool pressure','Pending connections'),
                            (axes[2,1],'Per-Pod DB pool usage','Active connections')]:
        ax.set_title(title)
        ax.set_ylabel(ylabel)
        if ax.lines:
            ax.legend(fontsize=8)
        else:
            ax.text(.5,.5,'Not collected',transform=ax.transAxes,ha='center',color='#64748b')
    if synthetic:
        for index,name in enumerate(n for n in names if n.endswith('/agent-harness-runtime')):
            label = 'Brain '+name.split('/')[0].split('-')[-1]
            active = [p['pods'].get(name,{}).get('managed_agents_loadtest_model_active',float('nan')) for p in points]
            chunks = [p['pods'].get(name,{}).get('managed_agents_loadtest_model_chunks',float('nan')) for p in points]
            axes[3,0].plot(xs,active,label=label,color=colors[index])
            rates = [(b-a)/(points[i+1]['timestamp']-points[i]['timestamp']) for i,(a,b) in enumerate(zip(chunks,chunks[1:]))]
            axes[3,1].plot(xs[1:],rates,label=label,color=colors[index])
        for ax,title,ylabel in [(axes[3,0],'Active synthetic model streams','Streams'),
                                (axes[3,1],'Synthetic model output rate','Chunks/s')]:
            ax.set_title(title);ax.set_ylabel(ylabel);ax.legend(fontsize=8)
    for ax in axes.flat:
        ax.set_xlabel('Minutes after first valid sample')
        ax.set_ylim(bottom=0)
        ax.grid(alpha=.2)
    accepted = summary.get('closed_loop_passed',summary.get('capacity_candidate_passed',False))
    status = 'ACCEPTED' if accepted else 'NOT ACCEPTED'
    target = (str(summary['target_inflight_sessions'])+' in-flight clients') if summary.get('mode')=='closed_loop' else str(summary['target_session_rps'])+' Sessions/s'
    fig.suptitle(f"{summary['batch']} | {target} | {status}\n"
        f"Durable peak: {summary['peak_active_turns']} Turns / {summary['peak_active_sessions']} Sessions; "
        f"observed completion: {summary['steady_completion_rps']:.3f} Sessions/s",fontsize=13)
    args.out.parent.mkdir(parents=True,exist_ok=True)
    fig.savefig(args.out,dpi=160)
    plt.close(fig)


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage',required=True,type=Path)
    parser.add_argument('--out',required=True,type=Path)
    run(parser.parse_args())
