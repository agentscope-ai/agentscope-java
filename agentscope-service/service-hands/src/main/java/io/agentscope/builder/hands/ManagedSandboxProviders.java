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
package io.agentscope.builder.hands;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedExecutionTargets;
import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentscope.builder.runtime.managed.ManagedLifecycles;
import io.agentscope.builder.runtime.managed.ManagedRuntimeLog;
import io.agentscope.builder.runtime.managed.ManagedSandboxBindings;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedStateException;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor;
import io.agentscope.builder.runtime.managed.ManagedTransportException;
import io.agentscope.builder.runtime.managed.RedisManagedInfrastructure;
import io.agentscope.builder.web.config.BuilderE2bProperties;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import io.agentscope.extensions.sandbox.e2b.E2bSandbox;
import io.agentscope.extensions.sandbox.e2b.E2bSandboxClient;
import io.agentscope.extensions.sandbox.e2b.E2bSandboxClientOptions;
import io.agentscope.extensions.sandbox.e2b.E2bSandboxState;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Provider state is durable and sealed; self-hosted providers are reached by outbound Workers. */
@Service
public final class ManagedSandboxProviders {
    private static final Logger log = LoggerFactory.getLogger(ManagedSandboxProviders.class);
    private final ManagedExecutionTargets catalog;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;
    private final RedisManagedInfrastructure redis;
    private final ManagedSandboxBindings bindings;
    private final boolean localTest;
    private final BuilderE2bProperties defaults;
    private final int cacheCapacity;
    private final Path localRoot;
    private final Map<String, Entry> sandboxes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepalive = Executors.newScheduledThreadPool(4);

    private record Entry(Sandbox sandbox, ManagedToolExecutor executor, long[] lastUsed) {}

    @Autowired
    public ManagedSandboxProviders(
            ManagedExecutionTargets catalog,
            ObjectMapper mapper,
            VaultCrypto crypto,
            RedisManagedInfrastructure redis,
            DataSource source,
            BuilderE2bProperties defaults,
            @Value("${builder.managed.hands.cached-sandboxes:1024}") int cacheCapacity,
            @Value("${builder.managed.hands.local-test-mode:false}") boolean localTest,
            @Value("${builder.managed.hands.local-directory:./data/managed-hands-test}")
                    String localRoot) {
        if (cacheCapacity < 1 || cacheCapacity > 4096)
            throw new IllegalArgumentException("Sandbox cache capacity must be 1..4096");
        this.defaults = defaults;
        this.cacheCapacity = cacheCapacity;
        this.catalog = catalog;
        this.mapper = mapper;
        this.crypto = crypto;
        this.redis = redis;
        this.bindings = new ManagedSandboxBindings(source);
        this.localTest = localTest;
        this.localRoot = Path.of(localRoot).toAbsolutePath().normalize();
        keepalive.scheduleWithFixedDelay(this::renew, 60, 60, TimeUnit.SECONDS);
    }

    public ManagedToolExecutor executor(ManagedScope scope) {
        var resolved = catalog.resolve(scope);
        if (resolved.environment() != null && "self_hosted".equals(resolved.environment().type()))
            return null;
        return new ManagedToolExecutor() {
            @Override
            public boolean streamsOutput(ToolTask task) {
                return attached(scope).streamsOutput(task);
            }

            @Override
            public OutputPage output(ManagedScope owner, ToolTask task, long offset) {
                return attached(owner).output(owner, task, offset);
            }

            @Override
            public String start(ManagedScope owner, ToolTask task) {
                return attached(owner).start(owner, task);
            }

            @Override
            public Outcome query(ManagedScope owner, ToolTask task) {
                return attached(owner).query(owner, task);
            }

            @Override
            public Outcome cancel(ManagedScope owner, ToolTask task) {
                return attached(owner).cancel(owner, task);
            }
        };
    }

    private ManagedToolExecutor attached(ManagedScope scope) {
        if (!sandboxes.containsKey(scope.messageGroup()) && sandboxes.size() >= cacheCapacity) {
            long cutoff = System.nanoTime() - Duration.ofSeconds(120).toNanos();
            sandboxes.forEach(
                    (key, entry) -> {
                        synchronized (entry.lastUsed()) {
                            if (entry.lastUsed()[0] < cutoff) sandboxes.remove(key, entry);
                        }
                    });
            if (sandboxes.size() >= cacheCapacity)
                throw new ManagedTransportException("Sandbox cache capacity reached");
        }
        return sandboxes.computeIfAbsent(scope.messageGroup(), ignored -> open(scope)).executor();
    }

    private Entry open(ManagedScope scope) {
        try {
            var resolved = catalog.resolve(scope);
            if (localTest) {
                Path directory = localRoot.resolve(scope.messageGroup());
                if (bindings.get(scope) != null && !Files.isDirectory(directory))
                    throw new ManagedStateException(
                            "sandbox_creation_unknown",
                            "Original local Sandbox directory is missing");
                bindings.borrow(
                        scope, ManagedScope.fingerprint("local-test", directory.toString()));
                Files.createDirectories(directory);
                stageLocal(directory, resolved.definitionFiles());
                var executor =
                        new ManagedSandboxToolExecutor(
                                mapper,
                                command -> {
                                    Path output =
                                            Files.createTempFile("managed-hands-control-", ".json");
                                    var process =
                                            new ProcessBuilder("sh")
                                                    .directory(directory.toFile())
                                                    .redirectOutput(output.toFile())
                                                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                                                    .start();
                                    try {
                                        try (var input = process.getOutputStream()) {
                                            input.write(command.getBytes(StandardCharsets.UTF_8));
                                        }
                                        if (!process.waitFor(30, TimeUnit.SECONDS))
                                            throw new IllegalStateException("Control timed out");
                                        if (process.exitValue() != 0
                                                || Files.size(output) > 64 * 1024 * 1024)
                                            throw new IllegalStateException(
                                                    "Control failed or exceeded its output limit");
                                        return Files.readString(output);
                                    } finally {
                                        process.destroyForcibly();
                                        Files.deleteIfExists(output);
                                    }
                                });
                ManagedRuntimeLog.info(
                        log,
                        "hands.sandbox.attached",
                        scope,
                        null,
                        "sandbox_kind",
                        "local_test",
                        "sandbox_id",
                        ManagedScope.fingerprint("local-test", directory.toString()));
                return new Entry(null, executor, new long[] {System.nanoTime()});
            }
            if (resolved.environment() == null)
                throw new ManagedStateException(
                        "environment_required",
                        "Managed tools require a cloud or self-hosted Environment");
            var options = options(resolved.environment().config());
            if (options.getApiKey() == null || options.getApiKey().isBlank())
                throw new ManagedStateException(
                        "sandbox_credentials_required", "Cloud Hands requires a provider API key");
            var client = new E2bSandboxClient(options, null);
            var lease =
                    redis.acquire(scope.key("sandbox-create"), Duration.ofSeconds(60))
                            .orElseThrow(
                                    () ->
                                            new ManagedTransportException(
                                                    "Sandbox creation is already in progress"));
            Sandbox sandbox;
            boolean reconnected;
            try {
                var saved = bindings.get(scope);
                reconnected = saved != null && saved.encryptedState() != null;
                if (saved != null && saved.encryptedState() != null) {
                    sandbox =
                            client.resume(
                                    client.deserializeState(
                                            crypto.decrypt(saved.encryptedState())));
                    sandbox.start();
                } else {
                    if (saved != null)
                        throw new ManagedStateException(
                                "sandbox_creation_unknown",
                                "Previous sandbox creation requires reconciliation");
                    var intent = bindings.begin(scope);
                    var workspace = new WorkspaceSpec();
                    workspace.setRoot(options.getWorkspaceRoot());
                    sandbox = client.create(workspace, null, null);
                    try {
                        sandbox.start();
                    } finally {
                        if (((E2bSandboxState) sandbox.getState()).getSandboxId() != null)
                            bindings.confirm(
                                    scope,
                                    intent.creationToken(),
                                    crypto.encrypt(client.serializeState(sandbox.getState())));
                    }
                }
                stageSandbox(sandbox, resolved.definitionFiles());
            } finally {
                redis.release(lease);
            }
            ManagedRuntimeLog.info(
                    log,
                    "hands.sandbox.attached",
                    scope,
                    null,
                    "sandbox_kind",
                    "cloud",
                    "sandbox_id",
                    ((E2bSandboxState) sandbox.getState()).getSandboxId(),
                    "attachment",
                    reconnected ? "reconnected" : "created");
            var lastUsed = new long[] {System.nanoTime()};
            var executor =
                    new ManagedSandboxToolExecutor(
                            mapper,
                            command -> {
                                synchronized (lastUsed) {
                                    lastUsed[0] = System.nanoTime();
                                }
                                var result = sandbox.exec(null, command, 30);
                                if (!result.ok())
                                    throw new IllegalStateException("Sandbox control failed");
                                return result.stdout();
                            });
            return new Entry(sandbox, executor, lastUsed);
        } catch (Exception unavailable) {
            throw new ManagedTransportException("Cannot attach Managed sandbox", unavailable);
        }
    }

    public void cleanup(ManagedLifecycles.Command command, Runnable check) {
        cleanupWorkspace(command, check);
        check.run();
    }

    private void cleanupWorkspace(ManagedLifecycles.Command command, Runnable check) {
        check.run();
        if (!"deleted".equals(command.desired())) return;
        var scope = command.scope();
        sandboxes.remove(scope.messageGroup());
        if (localTest) {
            Path directory = localRoot.resolve(scope.messageGroup());
            if (Files.exists(directory)) {
                try (var files = Files.walk(directory)) {
                    for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                        check.run();
                        Files.deleteIfExists(path);
                    }
                } catch (Exception failure) {
                    throw new ManagedTransportException(
                            "Local workspace deletion is unconfirmed", failure);
                }
            }
            bindings.releaseBorrowed(scope);
            return;
        }
        var binding = bindings.get(scope);
        if (binding == null || "released".equals(binding.status())) return;
        if ("borrowed".equals(binding.status())) {
            check.run();
            bindings.releaseBorrowed(scope);
            return;
        }
        if (binding.encryptedState() == null)
            throw new ManagedStateException(
                    "sandbox_creation_unknown",
                    "Unconfirmed creation prevents deleting a replacement sandbox");
        try {
            var resolved = catalog.resolve(scope);
            var client = new E2bSandboxClient(options(resolved.environment().config()), mapper);
            var sandbox =
                    client.resume(
                            client.deserializeState(crypto.decrypt(binding.encryptedState())));
            check.run();
            sandbox.shutdown();
            check.run();
            bindings.released(scope);
        } catch (ManagedStateException invalid) {
            throw invalid;
        } catch (Exception failure) {
            throw new ManagedTransportException("Sandbox deletion is unconfirmed", failure);
        }
    }

    private E2bSandboxClientOptions options(Map<String, Object> environment) {
        var config = new LinkedHashMap<String, Object>(environment);
        config.putIfAbsent("templateId", defaults.getTemplateId());
        config.putIfAbsent("workspaceRoot", defaults.getWorkspaceRoot());
        config.putIfAbsent("sandboxTimeoutSeconds", defaults.getSandboxTimeoutSeconds());
        String key = defaults.getApiKey();
        if (key == null || key.isBlank()) key = System.getenv("BUILDER_E2B_API_KEY");
        if (key == null || key.isBlank()) key = System.getenv("E2B_API_KEY");
        if (key != null && !key.isBlank()) config.putIfAbsent("apiKey", key);
        if (!defaults.getApiBaseUrl().isBlank())
            config.putIfAbsent("apiBaseUrl", defaults.getApiBaseUrl());
        if (!defaults.getDomain().isBlank()) config.putIfAbsent("domain", defaults.getDomain());
        return ManagedSandboxOptions.from(mapper.valueToTree(config));
    }

    private void stageLocal(Path root, Map<String, String> definitions) throws Exception {
        Path marker = root.resolve(".managed-tools/definition-ready");
        if (Files.exists(marker)) return;
        if (definitions == null) return;
        for (var entry : definitions.entrySet()) {
            Path target = root.resolve(entry.getKey()).normalize();
            if (!target.startsWith(root))
                throw new IllegalArgumentException("Definition path leaves Workspace");
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) Files.writeString(target, entry.getValue());
        }
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "ready");
    }

    private void stageSandbox(Sandbox sandbox, Map<String, String> definitions) throws Exception {
        ManagedSandboxStaging.stage(sandbox, definitions, mapper);
    }

    private void renew() {
        for (var entry : sandboxes.values()) {
            if (!(entry.sandbox() instanceof E2bSandbox sandbox)) continue;
            long last;
            synchronized (entry.lastUsed()) {
                last = entry.lastUsed()[0];
            }
            if (System.nanoTime() - last > Duration.ofSeconds(90).toNanos()) continue;
            try {
                sandbox.keepAlive();
            } catch (Exception unavailable) {
                log.warn("Sandbox keepalive failed: {}", unavailable.toString());
            }
        }
    }

    @PreDestroy
    public void close() {
        keepalive.shutdownNow();
    }
}
