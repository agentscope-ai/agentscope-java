import {describe,it,expect} from 'vitest';
import {parseWorkflow,graphLayout} from './workflowModel';
describe('Workflow draft safety',()=>{
 it.each(['null','[]','42','{"nodes":{}}','{"nodes":[null]}','{"nodes":[],"edges":[null]}'])('handles parseable invalid shape %s without crashing',text=>{expect(parseWorkflow(text).error).toBeTruthy()});
 it('keeps an incomplete draft editable',()=>{expect(parseWorkflow('{"nodes":[{"key":"a","type":"team"}]}').spec?.nodes.length).toBe(1)});
 it('rejects invalid JSON with an actionable error',()=>expect(parseWorkflow('{').error).toBeTruthy());
});
it('lays out joins after their parents without hanging on cycles',()=>{
 const points=graphLayout(['join','b','a'],[{from:'a',to:'join'},{from:'b',to:'join'}]);expect(points[0].x).toBeGreaterThan(points[1].x);
 expect(graphLayout(['a','b'],[{from:'a',to:'b'},{from:'b',to:'a'}])).toHaveLength(2);
});
