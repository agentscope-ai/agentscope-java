from probe import *
s=json.loads((ROOT/'state.json').read_text())
def issue(name,typ,ref,prompt):
 x=api('/api/v1/issues',{'tenant':'default','namespace':'default','title':PREFIX+'-'+name,'description':prompt,'assigneeType':typ,'assigneeRef':ref},label='submit-'+name)
 s['issues'][name]=x; (ROOT/'state.json').write_text(json.dumps(s,ensure_ascii=False,indent=2));print(name,json.dumps(x,ensure_ascii=False)[:350])
issue('single-calc','agent',s['agents']['calc'],'计算 17×23+19，给出一个等式和最终答案。不要使用工具。预期输出 CALC=410。')
issue('single-analyst','agent',s['agents']['analyst'],'只依据给定材料：甲2023年销量12，2024年销量15；乙2023年销量20，2024年销量18。计算两家增速，并按2024销量降序排列。不要使用工具。')
issue('single-qoder','agent',s['agents']['qoder'],'计算 8×7-6，仅输出 QODER=50。不要调用任何工具。')
issue('single-codex','agent',s['agents']['codex'],'判断 97 是否为质数，说明只需要试除哪些质数。不要联网或使用工具。最终包含 CODEX=prime。')
issue('team-managed','team',s['teams']['managed'],'请实际分工并汇总。将17×23+19交给calculator成员，要求输出 CALC=410；将字符串 banana,apple,cherry 按字典序排序交给analyst成员，要求输出 SORT=apple,banana,cherry。必须真实创建两个子Issue并分配给指定成员，不得自己替代。收到两份结果后验收，最终输出 TEAM_OK;CALC=410;SORT=apple,banana,cherry 并完成run。子Issue标题使用 '+PREFIX+' 前缀。')
