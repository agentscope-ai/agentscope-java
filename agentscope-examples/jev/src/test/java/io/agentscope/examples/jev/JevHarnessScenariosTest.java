/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class JevHarnessScenariosTest {
    @Test
    void selectionDistinguishesExplicitNoneFromUncertaintyAndFailure() {
        var r = JevHarnessScenarios.selection();
        var all = List.of("query_order", "refund", "generate_response");
        assertEquals(List.of("query_order", "generate_response"), r.get("query"));
        assertEquals(all, r.get("conditional-refund"));
        assertEquals(List.of("generate_response"), r.get("small-talk"));
        assertEquals(all, r.get("uncertain"));
        assertEquals(all, r.get("backend-error"));
        assertEquals(all, r.get("shadow"));
    }

    @Test
    void routerChoosesOnceAndDoesNotReactivateAfterFallback() {
        var r = JevHarnessScenarios.routing();
        assertEquals(List.of("fast", "fast", "fast"), r.get("simple").dispatchedModels());
        assertEquals(List.of("strong", "strong", "strong"), r.get("complex").dispatchedModels());
        assertEquals(
                List.of("strong", "original", "original"),
                r.get("lost-availability").dispatchedModels());
        assertEquals(
                List.of("original", "original", "original"),
                r.get("incompatible-tools").dispatchedModels());
        assertEquals(
                List.of("original", "original", "original"), r.get("shadow").dispatchedModels());
        r.values().forEach(run -> assertEquals(1, run.judgments()));
    }
}
