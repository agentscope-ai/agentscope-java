/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
