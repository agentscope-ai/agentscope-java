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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolExecutionContext;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RuntimeContext")
class RuntimeContextTest {

    private static final class PojoA {
        final String id;

        PojoA(String id) {
            this.id = id;
        }
    }

    private interface Marker {}

    private static final class MarkerImpl implements Marker {
        final String id;

        MarkerImpl(String id) {
            this.id = id;
        }
    }

    private static final class PojoB {
        final int n;

        PojoB(int n) {
            this.n = n;
        }
    }

    @Test
    @DisplayName("empty() builds an empty, mutable context")
    void empty_isMutable() {
        RuntimeContext ctx = RuntimeContext.empty();
        assertNull(ctx.getSessionId());
        assertNull(ctx.getUserId());
        ctx.put("k", "v");
        assertEquals("v", ctx.get("k"));
    }

    @Test
    @DisplayName("builder sets session fields and string extras")
    void builderSessionAndStringExtras() {
        RuntimeContext ctx =
                RuntimeContext.builder().sessionId("sid-1").userId("u-1").put("extra", 42).build();
        assertEquals("sid-1", ctx.getSessionId());
        assertEquals("u-1", ctx.getUserId());
        assertEquals(Integer.valueOf(42), ctx.get("extra"));
    }

    @Test
    @DisplayName("typed get/put and keyed typed access")
    void typedAccess() {
        PojoA a = new PojoA("a");
        RuntimeContext ctx = RuntimeContext.builder().put(PojoA.class, a).build();
        assertSame(a, ctx.get(PojoA.class));
        PojoB b0 = new PojoB(1);
        PojoB b1 = new PojoB(2);
        ctx.put("one", PojoB.class, b0);
        ctx.put("two", PojoB.class, b1);
        assertSame(b0, ctx.get("one", PojoB.class));
        assertSame(b1, ctx.get("two", PojoB.class));
        ctx.put(PojoA.class, null);
        assertNull(ctx.get(PojoA.class));
    }

    @Test
    @DisplayName("get(Class) for RuntimeContext returns the instance")
    void selfTypedAccess() {
        RuntimeContext ctx = RuntimeContext.empty();
        assertSame(ctx, ctx.get(RuntimeContext.class));
        assertSame(ctx, ctx.get("", RuntimeContext.class));
    }

    @Test
    @DisplayName("asToolExecutionContext exposes typed values with higher priority than agent TEC")
    void asToolExecutionContextMergePriority() {
        PojoA fromRun = new PojoA("from-run");
        PojoA fromAgent = new PojoA("from-agent");
        RuntimeContext run = RuntimeContext.builder().put(PojoA.class, fromRun).build();
        ToolExecutionContext agent =
                ToolExecutionContext.builder().register(PojoA.class, fromAgent).build();
        ToolExecutionContext merged =
                ToolExecutionContext.merge(run.asToolExecutionContext(), agent);
        assertSame(fromRun, merged.get(PojoA.class));
    }

    @Test
    @DisplayName("fork copies extras and keeps call-scoped state isolated")
    void forkCopiesMetadataAndLeavesAgentStateNull() {
        ToolExecutionContext toolContext =
                ToolExecutionContext.builder().register(new PojoB(7)).build();
        RuntimeContext parent =
                RuntimeContext.builder()
                        .sessionId("parent-session")
                        .userId("parent-user")
                        .put("traceId", "trace-123")
                        .put(PojoA.class, new PojoA("typed-parent"))
                        .toolExecutionContext(toolContext)
                        .agentState(AgentState.builder().sessionId("agent-state").build())
                        .build();

        RuntimeContext child = parent.fork("child-session", null);
        RuntimeContext sibling = parent.fork(null, "child-user");

        assertEquals("child-session", child.getSessionId());
        assertEquals("parent-user", child.getUserId());
        assertEquals("trace-123", child.get("traceId"));
        assertSame(parent.get(PojoA.class), child.get(PojoA.class));
        assertSame(toolContext, child.getToolExecutionContext());
        assertNull(child.getAgentState());

        assertEquals("parent-session", sibling.getSessionId());
        assertEquals("child-user", sibling.getUserId());
        assertEquals("trace-123", sibling.get("traceId"));
        assertSame(toolContext, sibling.getToolExecutionContext());
        assertNull(sibling.getAgentState());

        parent.put("traceId", "changed-parent");
        child.put("child-only", "yes");
        assertEquals("trace-123", child.get("traceId"));
        assertNull(parent.get("child-only"));
    @DisplayName("builder(source) preserves typed and string extras")
    void builderCopyPreservesTypedData() {
        MarkerImpl filesystem = new MarkerImpl("filesystem");
        RuntimeContext source =
                RuntimeContext.builder()
                        .sessionId("sid-copy")
                        .userId("user-copy")
                        .put("plain", "value")
                        .put(MarkerImpl.class, filesystem)
                        .build();

        RuntimeContext copy = RuntimeContext.builder(source).build();

        assertEquals("sid-copy", copy.getSessionId());
        assertEquals("user-copy", copy.getUserId());
        assertEquals("value", copy.get("plain"));
        assertSame(filesystem, copy.get(MarkerImpl.class));
        assertSame(filesystem, copy.get(Marker.class));
        assertSame(copy, copy.get(RuntimeContext.class));
        assertInstanceOf(MarkerImpl.class, copy.get(Marker.class));
    }

    @Test
    @DisplayName("typed keyed access falls back to assignable singleton values")
    void keyedTypedAccessFallsBackToAssignableSingleton() {
        MarkerImpl filesystem = new MarkerImpl("filesystem");
        RuntimeContext ctx =
                RuntimeContext.builder().put("filesystem", MarkerImpl.class, filesystem).build();

        assertSame(filesystem, ctx.get("filesystem", Marker.class));
        assertSame(filesystem, ctx.get("filesystem", MarkerImpl.class));
        assertNull(ctx.get("missing", Marker.class));
    }

    @Test
    @DisplayName("builder(source) tolerates null and preserves empty contexts")
    void builderCopyHandlesNullSource() {
        RuntimeContext empty = RuntimeContext.builder((RuntimeContext) null).build();

        assertNull(empty.getSessionId());
        assertNull(empty.getUserId());
        assertNull(empty.get("missing", Marker.class));
    }

    @Test
    @DisplayName("concurrent puts on distinct keys from multiple threads")
    void threadSafety() throws Exception {
        RuntimeContext ctx = RuntimeContext.empty();
        int threads = 8;
        int per = 200;
        CyclicBarrier b = new CyclicBarrier(threads);
        AtomicInteger ok = new AtomicInteger();
        Thread[] t = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int fi = i;
            t[i] =
                    new Thread(
                            () -> {
                                try {
                                    b.await();
                                    for (int j = 0; j < per; j++) {
                                        String k = "k-" + fi + "-" + j;
                                        ctx.put(k, k);
                                        assertEquals(k, ctx.get(k));
                                    }
                                    ok.incrementAndGet();
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
            t[i].start();
        }
        for (Thread th : t) {
            th.join();
        }
        assertEquals(threads, ok.get());
    }
}
