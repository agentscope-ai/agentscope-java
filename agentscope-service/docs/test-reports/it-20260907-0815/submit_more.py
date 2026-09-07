from probe import *
s=json.loads((ROOT/'state.json').read_text())
def issue(name,typ,ref,prompt):
 x=api('/api/v1/issues',{'tenant':'default','namespace':'default','title':PREFIX+'-'+name,'description':prompt,'assigneeType':typ,'assigneeRef':ref},label='submit-'+name)
 s['issues'][name]=x; (ROOT/'state.json').write_text(json.dumps(s,ensure_ascii=False,indent=2));print(name,x.get('issue',{}).get('id'),x.get('error'))
issue('team-mixed-normal','team',s['teams']['mixed'],'请实际分工：researcher成员只根据给定事实“手机A电池5000mAh，手机B电池4000mAh”计算A比B多多少，期望 BATTERY=25%；calculator成员计算100+200，期望 SUM=300。必须分别真实委派两个子Issue，收集并验收后输出 MIXED_OK;BATTERY=25%;SUM=300 并完成run。无需联网或额外工具。子Issue标题前缀 '+PREFIX+'。')
issue('mention-managed','agent',s['agents']['calc'],f'''测试真实成员间mention。你是A（{s['agents']['calc']}），B是文本分析员（{s['agents']['analyst']}）。先用 issue.comment.add 创建一条 comment：content="请对pear,apple进行排序，返回 MENTION_B=apple,pear，并用结构化mention回复A一次。", mentions=[{{"type":"agent","ref":"{s['agents']['analyst']}"}}]。这必须是真实工具调用，不能只输出JSON或@文字。然后立即结束本次任务，勿本地等待。B收到后必须用issue.comment.add真实回复一次，并结构化mention A（{s['agents']['calc']}），消息中含MENTION_B=apple,pear。A被回复唤醒后，只输出 MENTION_ACK，禁止再次mention任何人，以免循环。''')
issue('research-microservices','team',s['teams']['mixed'],'请研究截至2026年9月的微服务行业趋势。必须由researcher成员实际尝试当前可用的联网/检索工具，取得至少2条2026年发布来源，包含标题、日期、URL；不要仅凭训练知识给答案。明确要求researcher先尝试一次WebSearch搜索“2026 microservices trends”，若不存在此工具，尝试可用的等效联网工具；工具被拒绝或不存在就如实报告工具名和错误，不得绕过权限、请求扩大权限、安装工具或修改配置。calculator成员只计算工具无关的基线2+3=5。协调员实际委派并收集结果；如果无法核实2026来源，明确判定研究目标失败并终结run为failed，禁止假装研究成功。子Issue标题前缀 '+PREFIX+'。')
issue('research-phone','agent',s['agents']['qoder'],'请研究截至2026年9月的手机行业趋势，必须实际尝试WebSearch或当前可用联网工具搜索“2026 smartphone market shipments”，取得2条2026年发布来源，含标题、日期、URL。不允许用记忆替代、不安装工具、不修改权限或配置。若工具被拒绝，保留错误并明确未完成实时研究，结束本轮，不无限重试。')
# Control has no assignee: plain text mention should not deliver anything.
issue('mention-text-control','','','用于对照纯文本@与结构化mentions，没有默认assignee。')
iid=s['issues']['mention-text-control']['issue']['id']
api('/api/v1/issues/'+iid+'/comments',{'content':'@'+PREFIX+'-analyst 请只输出 TEXT_ONLY_SHOULD_NOT_WAKE','mentions':[]},label='text-mention-comment')
