import { describe,expect,it } from 'vitest';
import { allowedRunControls,projectGraph,validateDefinitionShape } from './model';

describe('orchestration console model',()=>{
  it('validates definition endpoints and duplicate keys',()=>{
    expect(validateDefinitionShape({nodes:[{key:'a',type:'agent'},{key:'a',type:'agent'}],edges:[{from:'a',to:'missing'}]})).toEqual([
      'duplicate node key: a','unknown edge endpoint: a -> missing',
    ]);
  });
  it('projects graph ownership',()=>{
    const projected=projectGraph({run:{id:'r',tenant:'t',namespace:'n',rootIssueId:'i',mode:'declared',triggerType:'test',state:'running',version:1,createdAt:''},nodes:[{id:'n1',runId:'r',nodeKey:'one',type:'agent',state:'waiting',version:1},{id:'n2',runId:'r',nodeKey:'two',type:'join',state:'pending',version:1}],edges:[{id:'e',runId:'r',fromNodeId:'n1',toNodeId:'n2',onStates:['succeeded'],ordinal:0}],tasks:[{id:'t1',agentId:'a',status:'running',runNodeId:'n1'}],attempts:[{id:'a1',agentTaskId:'t1',runId:'r',nodeId:'n1',attempt:1,dispatchGeneration:1,backendKind:'managed',state:'running',createdAt:''}]});
    expect(projected[0]).toMatchObject({outgoing:['n2'],tasks:['t1'],attempts:['a1']});
    expect(projected[1].incoming).toEqual(['n1']);
  });
  it('offers only legal run controls',()=>{
    expect(allowedRunControls('running')).toEqual(['pause','cancel']);
    expect(allowedRunControls('paused')).toEqual(['resume','cancel']);
    expect(allowedRunControls('succeeded')).toEqual([]);
  });
});
