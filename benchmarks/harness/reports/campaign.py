# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Merge explicitly justified infrastructure recovery without replacing original outcomes."""
import argparse
import copy
import hashlib
import html
import json
from pathlib import Path
from reports.full_run import overview
from reports.unified import render, summarize


def annotations(row):
    task=row['task'];agent=row['agent'];notes=[]
    if task.startswith('tau-'):
        notes.append('业务归档存在浅引用快照问题：早期 user_model.request 历史和 initial_business_state.data 可能反映后续状态。逐时分析应交叉核对原生事件、工具请求与已校验的最终状态，不能直接把这些字段当实时快照。')
    if task=='file-empty':
        notes.append('题目未规定 JSON 字段名，评分却要求 bytes/lines；不能据此判断空文件处理能力。')
    if task in ('skill-discovery','skill-permission'):
        notes.append('技能要求 RELEASE: 后接值，但未约定空格；精确文本评分存在歧义。技能加载和权限行为应单独查看。')
    if task=='memory-persist' and agent=='agentscope':
        notes.append('适配器没有传入稳定 userId，两个 session 实际使用不同内存目录；不是有效的同用户跨会话记忆探针。')
    if task=='session-restart':
        notes.append('前一轮禁止将 marker 写入任何文件，后一轮又要求写入文件；保留旧约束造成的拒绝不能解释为记忆丢失。')
    checks=row.get('behavior_checks',{})
    if task.startswith('compact-') and any('compact' in key and not value for key,value in checks.items()):
        notes.append('未观测到要求的原生压缩事件，探针前提未触发；不能据此断言压缩后丢失上下文。')
    if task=='session-cancel':
        notes.append('本探针测量 CLI 进程组终止及延迟副作用，不等同于服务端持久会话取消 API。')
    if task=='session-cancel' and row.get('score')==1 and not row.get('protocol_valid'):
        notes.append('取消行为通过，归档事件通过 schema 与引用校验。protocol_valid 实际要求同时覆盖消息与工具调用/返回；取消会截断回答或工具返回，缺少这些事件不能据此判断格式不合规。')
    if task in ('subagent-delegate','subagent-failure') and agent=='codex':
        notes.append('原生委派入口存在，但子 Agent 的模型选择与当前 qwen provider 配置不兼容；委派失败与失败传播题均存在适配配置干扰。')
    if task=='tb-write-compressor' and agent=='agentscope':
        notes.append('Runner 内部默认单轮超时 600 秒，未与外层 1800 秒对齐；原生日志在约 600 秒退出，属于适配器时间预算干扰，不能直接与其他 Agent 比较。')
    if task=='tb-write-compressor' and agent=='codex':
        notes.append('在外层 1800 秒执行上限内未完成，原生记录仍在尝试算法；官方对最终产物评分为零。保留超时与得分两个维度。')
    if task=='tb-large-scale-text-editing' and agent=='pi':
        notes.append('原生响应以 stopReason=length 结束，单次输出达到 16384 tokens，未出现成功完成事件；之后评分器上传又失败。既不能重跑答案，也不满足本轮完成答案的仅评分恢复条件。')
    if task=='tb-large-scale-text-editing' and agent=='agentscope':
        notes.append('仅评分恢复得到 4/5 检查通过。最终文本与独立生成的官方参考哈希一致，但官方在最终文件上再次运行宏时报 E486、返回 1；失败点在重复执行时的无匹配处理，保留官方复核的零分。')
    if task=='tau-41' and agent in ('agentscope','codex'):
        notes.append('先修改商品使订单进入禁止继续修改的状态，再更新地址失败；用户此前已确认两项需求。属于不可逆操作的顺序规划失败，不能单凭此归因于 Java 工具实现。')
    if task=='tb-polyglot-c-py':
        if agent=='agentscope':
            notes.append('达到 60 轮执行上限，未完成题目；原始分数为 null，但端到端验收不能将其遗漏。')
        else:
            notes.append('官方首个断言发现目录多出编译产物 cmain，违反单文件交付要求；尚未执行到 Fibonacci 正确性检查，失败点是最终工作区清理。')
    if task=='tau-3' and agent=='pi':
        notes.append('模拟用户拥有题目给定的邮编 19122，却回答不记得邮编且没有邮箱；Pi 按身份核验政策转人工，后续商品/订单任务未执行。零分受用户模拟交互影响，不能据此断言其不会调用业务工具。')
    if task=='tau-22' and agent in ('pi','agentscope'):
        notes.append('官方零分的状态差异已定位到订单国家字段 United States / USA；在内存副本中只归一化该字段后，状态哈希与官方参考完全一致。原始评分保持不变。')
    if task=='tau-22' and agent=='codex':
        notes.append('全部请求的业务操作已完成，但订单 state/country 使用 New York / United States，官方参考要求 NY / USA；完整状态对比已证实仅这两处表示差异，保留官方零分。')
    if agent=='agentscope' and task in ('appworld-23cf851_1','appworld-37a8675_1','appworld-4ec8de5_1','appworld-4fab96f_1','appworld-68ee2c9_1','appworld-df61dc5_1'):
        notes.append('回答使用真实日期 2026-11，但模拟世界日期为 2023-05-18；Harness 注入系统日期，评测适配层未显式提供世界时间，存在时间上下文干扰。')
    if agent=='codex' and task in ('appworld-23cf851_1','appworld-4ec8de5_1'):
        notes.append('使用真实运行日期 2026 年来解释相对时间，提交 0；模拟世界日期为 2023-05-18。属于共享评测时钟缺失，不是单独的 AgentScope 日期注入问题，具体参考答案见官方断言。')
    if agent=='codex' and task=='appworld-68ee2c9_1':
        notes.append('最终回答称按 2026 年判断“今年”，将全部 100 个文件移入回收站；模拟世界为 2023 年。该时钟干扰与官方评分器缺表异常分别记录，未评分不等同于通过。')
    if row.get('unscored_reason')=='evaluator_exception':
        notes.append('官方评分器执行异常，保留未评分状态；任务是否完成与评分器能否正常返回需要分别判断。')
        if 'no such table: files' in str(row.get('official_result')):
            notes.append('AppWorld 读取评分起始状态时缺少 files 表，复现此前已知的评分数据库问题；不能用该 null 分数判断候选任务成败。')
    if task=='appworld-383cbac_1' and agent=='pi':
        notes.append('金额计算得到正确的 227，但提交了带货币符号的 "$227"；官方评分要求数字答案，属于输出格式失败。')
    if task=='appworld-3ab5b8b_1' and agent=='agentscope':
        notes.append('轨迹显示未按已提供的 execute_code schema 调用，而是尝试本地导入和不存在的业务工具名；CLI 的 HTTP 500 未展示业务错误正文，进一步妨碍纠错。')
    if task in ('appworld-0d8a4ee_1','appworld-57c3486_1','appworld-d4e9306_1') and agent=='codex':
        notes.append('归档请求包含 execute_code schema，但轨迹尝试把 supervisor/spotify API 名直接作为 CLI 工具名；没有正确使用 Python 执行入口，不能据其最终回答断言环境未注册工具。')
    if task=='appworld-3ab5b8b_1' and agent in ('codex','pi'):
        notes.append('下载“喜欢的歌单”中的 43 首歌曲，官方要求“我的歌单歌曲”与“已喜欢歌曲”的交集 21 首。原句定语附着存在歧义；保留官方零分，不能将下载成功等同于选对集合。')
    return notes


def merge(manifest, original, recoveries=(), verifications=()):
    rows={r['trial_id']:copy.deepcopy(r) for r in original['results']}
    applied=set();source_run=manifest['run_id']
    for row in rows.values():
        row['original_outcome']={k:row.get(k) for k in ('score','execution_status','scoring_status','error_type')}
        row['result_run']=source_run
    for recovery_manifest, summary, eligibility in recoveries:
        if recovery_manifest.get('recovery_of',{}).get('run_id')!=source_run or eligibility['source_run']!=source_run:
            raise ValueError('Recovery source mismatch')
        allowed={x['source_trial'] for x in eligibility['selected']}
        for recovered in summary['results']:
            name=recovered['trial_id'];old=rows[name]
            if name not in allowed or name in applied or old.get('score') is not None or old.get('native_completed'):
                raise ValueError('Recovery would replace a model execution or already-scored result')
            if (old['agent'],old['task'],old['rollout'])!=(recovered['agent'],recovered['task'],recovered['rollout']):
                raise ValueError('Recovery trial identity mismatch')
            replacement=copy.deepcopy(recovered)
            replacement.update(original_outcome=old['original_outcome'],result_run=recovery_manifest['run_id'],
                recovery_kind='first model execution after proven pre-agent startup failure')
            rows[name]=replacement;applied.add(name)
    for run_id,summary in verifications:
        if summary['source_run']!=source_run or summary.get('model_calls')!=0:
            raise ValueError('Verifier recovery must use the source run and zero model calls')
        for verified in summary['results']:
            name=verified['source_trial'];old=rows[name]
            if name in applied or old.get('score') is not None or not old.get('native_completed'):
                raise ValueError('Verifier recovery would overwrite an existing score or unfinished answer')
            old['verification_recovery']={'run_id':run_id,**verified}
            if verified.get('scoring_status')=='scored':
                old.update(score=verified['score'],scoring_status='scored',verification_run=run_id,
                    verifier_checks=verified.get('verifier_checks',old.get('verifier_checks',{})))
                # Keep the original execution error visible: only grading was repaired.
            applied.add(name)
    for row in rows.values():
        row['audit_notes']=annotations(row)
        if row.get('archive_path'):row['archive_path']='runs/'+row['result_run']+'/'+row['archive_path']
    return summarize(manifest,list(rows.values()))


def write_report(manifest, summary, output):
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    value=overview(manifest,summary)
    value['audit_annotated_cells']=sum(bool(r.get('audit_notes')) for r in summary['results'])
    for name,data in (('manifest.json',manifest),('summary.json',summary),('overview.json',value)):
        (output/name).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    tables=[]
    for title,key in [('按 Agent','by_agent'),('按任务类别','by_category')]:
        text='<h2>'+title+'</h2><table><tr><th>分组</th><th>计划</th><th>评分</th><th>通过</th><th>零分</th><th>不支持</th><th>其他未评分</th></tr>'
        for label,item in value[key].items():
            text+='<tr><td>'+html.escape(label)+'</td>'+''.join('<td>'+str(item[k])+'</td>' for k in ('planned','scored','passed','failed_score','adapter_unsupported','other_unscored'))+'</tr>'
        tables.append(text+'</table>')
    notes='<h2>解释与恢复记录</h2><p>原始评分不被覆盖。下表合并有证据支持的基础设施恢复；题目歧义与适配器限制仍需单独解释，不能直接作为产品排行榜。</p>'
    for row in summary['results']:
        if not row.get('audit_notes') and not row.get('recovery_kind') and not row.get('verification_recovery'):continue
        notes+='<details><summary>'+html.escape(row['agent']+' / '+row['task'])+'</summary><pre>'+html.escape(json.dumps({k:row.get(k) for k in ('original_outcome','result_run','recovery_kind','verification_recovery','audit_notes')},ensure_ascii=False,indent=2))+'</pre></details>'
    agents=[item['name'] for item in manifest['agents']]
    matrix='<h2>逐题对照</h2><p>绿色通过，红色零分，灰色未评分；⚠ 表示解释限制。点击结果展开原始题目、回答与轨迹。</p><table><tr><th>题目</th>'+''.join('<th>'+html.escape(a)+'</th>' for a in agents)+'</tr>'
    index={(r['agent'],r['task'],r['rollout']):r for r in summary['results']}
    for task in manifest['tasks']:
        matrix+='<tr><td>'+html.escape(task)+'</td>'
        for agent in agents:
            row=index.get((agent,task,0),{})
            score=row.get('score')
            label='通过' if score==1 else '零分' if score==0 else '不支持' if row.get('execution_status')=='adapter_unsupported' else '待完成' if row.get('execution_status')=='missing' else '未评分'
            color='#ddf4e4' if score==1 else '#fde2df' if score==0 else '#edf0f4'
            warning=' ⚠' if row.get('audit_notes') else ''
            identity=html.escape(agent+'-'+task+'-r0')
            matrix+='<td style="background:'+color+'"><a href="#'+identity+'" onclick="const d=document.getElementById(this.hash.slice(1));d.hidden=false;d.open=true">'+label+warning+'</a></td>'
        matrix+='</tr>'
    matrix+='</table>'
    report=render(manifest,summary).replace('<input id="filter"',''.join(tables)+matrix+notes+'<input id="filter"',1)
    (output/'report.html').write_text(report)
