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
package io.agentscope.examples.documentation2.harness.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.redis.RedisAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.RedisStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import redis.clients.jedis.JedisPooled;

/**
 * WorkspaceSharedStoreExample — Filesystem mode 1 (shared store): multiple replicas share the
 * same long-term memory via a KV store backed by Redis.
 *
 * <p>What this example shows:
 * <ol>
 *   <li><b>{@link RemoteFilesystemSpec}</b> — routes memory, sessions, skills, and subagent
 *       data through a shared {@link BaseStore} (Redis-backed).</li>
 *   <li><b>{@link IsolationScope}</b> — {@code USER} scope means each user's memory and
 *       sessions live in an isolated namespace; {@code AGENT} scope shares everything.</li>
 *   <li><b>Multi-replica simulation</b> — two agent instances backed by the same Redis store
 *       demonstrate cross-replica memory continuity.</li>
 * </ol>
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>A running Redis instance (default: {@code localhost:6379}).</li>
 *   <li>DashScope API key set as {@code DASHSCOPE_API_KEY}.</li>
 * </ul>
 *
 * <p>To start Redis quickly with Docker:
 * <pre>
 *   docker run -d --name redis -p 6379:6379 redis:7
 * </pre>
 *
 * <p>To override the Redis URL, set the {@code REDIS_URL} environment variable:
 * <pre>
 *   export REDIS_URL=redis://your-redis-host:6379
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.workspace.WorkspaceSharedStoreExample
 * </pre>
 */
public class WorkspaceSharedStoreExample {

    private static final String DEFAULT_REDIS_URL = "redis://localhost:6379";

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Filesystem Mode 1 — Shared Store (multi-replica, multi-user)");
        System.out.println("=".repeat(60) + "\n");

        String redisUrl = System.getenv().getOrDefault("REDIS_URL", DEFAULT_REDIS_URL);
        System.out.println("Connecting to Redis: " + redisUrl + "\n");

        // Shared infrastructure backed by Redis.
        // Both RedisStore (file store) and RedisAgentStateStore (agent state) share the
        // same Jedis connection, using different key prefixes to avoid collisions.
        JedisPooled jedis = new JedisPooled(java.net.URI.create(redisUrl));

        RedisStore sharedStore = new RedisStore(jedis, "agentscope:example:store:");
        RedisAgentStateStore sharedStateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(jedis)
                        .keyPrefix("agentscope:example:session:")
                        .build();

        Path workspace = Files.createTempDirectory("agentscope-shared-store-example");

        Files.writeString(
                workspace.resolve("AGENTS.md"),
                """
                # Shared Store Agent

                You are a helpful assistant.
                Keep answers concise (under two sentences).
                """);

        // ── Build "replica 1" ───────────────────────────────────────────────

        System.out.println("── Building replica-1 and replica-2 with shared store ──\n");

        HarnessAgent replica1 =
                HarnessAgent.builder()
                        .name("shared-agent")
                        .sysPrompt("You are a helpful assistant.")
                        .model("qwen-plus")
                        .workspace(workspace)
                        .stateStore(sharedStateStore)
                        .filesystem(
                                new RemoteFilesystemSpec(sharedStore)
                                        .isolationScope(IsolationScope.USER))
                        .build();

        // ── Alice talks to replica-1 ────────────────────────────────────────

        System.out.println("── Alice talks to replica-1 ──\n");

        RuntimeContext aliceCtx =
                RuntimeContext.builder().userId("alice").sessionId("alice-s1").build();

        Msg r1 =
                replica1.call(new UserMessage("My favorite color is blue. Remember it."), aliceCtx)
                        .block();
        System.out.println("Replica-1 reply: " + (r1 != null ? r1.getTextContent() : "(null)"));

        // ── Bob talks to replica-1 (isolated from Alice) ────────────────────

        System.out.println("\n── Bob talks to replica-1 (separate namespace) ──\n");

        RuntimeContext bobCtx = RuntimeContext.builder().userId("bob").sessionId("bob-s1").build();

        Msg r2 =
                replica1.call(new UserMessage("My favorite color is red. Remember it."), bobCtx)
                        .block();
        System.out.println("Replica-1 reply: " + (r2 != null ? r2.getTextContent() : "(null)"));

        // ── Build "replica 2" — same Redis store, same state store ──────────

        HarnessAgent replica2 =
                HarnessAgent.builder()
                        .name("shared-agent")
                        .sysPrompt("You are a helpful assistant.")
                        .model("qwen-plus")
                        .workspace(workspace)
                        .stateStore(sharedStateStore)
                        .filesystem(
                                new RemoteFilesystemSpec(sharedStore)
                                        .isolationScope(IsolationScope.USER))
                        .build();

        // ── Alice resumes on replica-2 (cross-replica continuity) ───────────

        System.out.println("\n── Alice resumes on replica-2 (cross-replica continuity) ──\n");

        Msg r3 = replica2.call(new UserMessage("What is my favorite color?"), aliceCtx).block();
        System.out.println(
                "Replica-2 reply (Alice): " + (r3 != null ? r3.getTextContent() : "(null)"));

        // ── Bob resumes on replica-2 ────────────────────────────────────────

        System.out.println("\n── Bob resumes on replica-2 ──\n");

        Msg r4 = replica2.call(new UserMessage("What is my favorite color?"), bobCtx).block();
        System.out.println(
                "Replica-2 reply (Bob): " + (r4 != null ? r4.getTextContent() : "(null)"));

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Done. Both users' state survived cross-replica migration.");
        System.out.println("=".repeat(60));
    }
}
