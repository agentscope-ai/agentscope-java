/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase.ExtensionPoint;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Unit tests for both dimensions of {@link ActivePointsResolver}: middleware → points
 * ({@code resolve}/{@code infer}) and points → middleware ({@code middlewaresAt}/{@code
 * groupByPoint}).
 */
class ActivePointsResolverTest {

    /** Implements exactly the reasoning hook; undeclared. */
    private static final class ReasoningOnly implements MiddlewareBase {
        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext ctx,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            return next.apply(input);
        }
    }

    /** Implements the reasoning hook but declares only ON_AGENT — the declaration wins. */
    private static final class DeclaredElsewhere implements MiddlewareBase {
        @Override
        public Set<ExtensionPoint> activePoints() {
            return EnumSet.of(ExtensionPoint.ON_AGENT);
        }

        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext ctx,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            return next.apply(input);
        }
    }

    @Test
    void inferReturnsExactlyImplementedHooks() {
        assertEquals(
                EnumSet.of(ExtensionPoint.ON_REASONING),
                ActivePointsResolver.infer(ReasoningOnly.class));
        assertTrue(
                ActivePointsResolver.infer(new MiddlewareBase() {}.getClass()).isEmpty(),
                "a middleware implementing no hooks infers an empty set");
    }

    @Test
    void resolveExplicitDeclarationBeatsInference() {
        assertEquals(
                EnumSet.of(ExtensionPoint.ON_REASONING),
                ActivePointsResolver.infer(DeclaredElsewhere.class));
        assertEquals(
                EnumSet.of(ExtensionPoint.ON_AGENT),
                ActivePointsResolver.resolve(new DeclaredElsewhere()));
    }

    @Test
    void middlewaresAtFiltersByPointPreservingSuppliedOrder() {
        ReasoningOnly reasoning = new ReasoningOnly();
        DeclaredElsewhere declared = new DeclaredElsewhere();
        MiddlewareBase bare = new MiddlewareBase() {};
        List<MiddlewareBase> candidates = List.of(declared, reasoning, bare);

        assertEquals(
                List.of(reasoning),
                ActivePointsResolver.middlewaresAt(ExtensionPoint.ON_REASONING, () -> candidates));
        assertEquals(
                List.of(declared),
                ActivePointsResolver.middlewaresAt(ExtensionPoint.ON_AGENT, () -> candidates));
        assertTrue(
                ActivePointsResolver.middlewaresAt(
                                ExtensionPoint.ON_SYSTEM_PROMPT, () -> candidates)
                        .isEmpty());

        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        ActivePointsResolver.middlewaresAt(
                                        ExtensionPoint.ON_REASONING, () -> candidates)
                                .add(reasoning),
                "the filtered result must be unmodifiable");
    }

    @Test
    void groupByPointProducesUnmodifiableGrouping() {
        ReasoningOnly reasoning = new ReasoningOnly();
        DeclaredElsewhere declared = new DeclaredElsewhere();
        Map<ExtensionPoint, List<MiddlewareBase>> grouped =
                ActivePointsResolver.groupByPoint(List.of(declared, reasoning));

        assertEquals(List.of(reasoning), grouped.get(ExtensionPoint.ON_REASONING));
        assertEquals(List.of(declared), grouped.get(ExtensionPoint.ON_AGENT));
        assertNull(grouped.get(ExtensionPoint.ON_SYSTEM_PROMPT), "empty points are absent");
        assertThrows(
                UnsupportedOperationException.class,
                () -> grouped.put(ExtensionPoint.ON_ACTING, List.of()),
                "the grouping must be unmodifiable");
        assertThrows(
                UnsupportedOperationException.class,
                () -> grouped.get(ExtensionPoint.ON_REASONING).add(reasoning),
                "participant lists must be unmodifiable");
    }

    @Test
    void nullArgumentsFailFast() {
        assertThrows(NullPointerException.class, () -> ActivePointsResolver.resolve(null));
        assertThrows(
                NullPointerException.class,
                () -> ActivePointsResolver.middlewaresAt(ExtensionPoint.ON_REASONING, null));
        assertThrows(
                NullPointerException.class,
                () -> ActivePointsResolver.middlewaresAt(null, List::of));
        assertThrows(
                NullPointerException.class,
                () -> ActivePointsResolver.middlewaresAt(ExtensionPoint.ON_REASONING, () -> null));
        assertThrows(NullPointerException.class, () -> ActivePointsResolver.groupByPoint(null));
    }
}
