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
package io.agentcore.managed.server.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedToolEvents;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor;
import io.agentcore.managed.server.runtime.managed.ManagedToolResult;
import io.agentcore.managed.server.runtime.managed.ManagedWorkerProtocol;
import io.agentcore.managed.server.toolworker.ManagedSandboxOptions;
import io.agentcore.managed.server.toolworker.ManagedSandboxStaging;
import io.agentcore.managed.server.toolworker.ManagedSandboxToolExecutor;
import io.agentscope.extensions.sandbox.e2b.E2bSandboxClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Outbound-only self_hosted Worker. Persisted outcomes are uploaded until Tool Worker confirms DB commit. */
public final class ManagedEnvironmentWorker implements AutoCloseable {
    @FunctionalInterface
    interface SandboxExecutors {
        ManagedToolExecutor open(ManagedWorkerProtocol.Assignment assignment) throws Exception;
    }

    private final SandboxExecutors sandboxExecutors;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI base;
    private final String environment;
    private final String key;
    private final String worker;
    private final Path results;
    private final ExecutorService executions;
    private final ScheduledExecutorService renewals;
    private final ScheduledExecutorService uploads = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore slots;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public ManagedEnvironmentWorker(
            URI base, String environment, String key, Path root, int concurrency) throws Exception {
        this(base, environment, key, root, concurrency, null);
    }

    ManagedEnvironmentWorker(
            URI base,
            String environment,
            String key,
            Path root,
            int concurrency,
            SandboxExecutors sandboxExecutors)
            throws Exception {
        if (!"https".equals(base.getScheme())
                && !("http".equals(base.getScheme())
                        && List.of("localhost", "127.0.0.1", "[::1]", "::1")
                                .contains(base.getHost())))
            throw new IllegalArgumentException(
                    "External Worker requires HTTPS; loopback HTTP is allowed for local testing");
        if (environment == null
                || environment.isBlank()
                || key == null
                || key.isBlank()
                || concurrency < 1
                || concurrency > 32)
            throw new IllegalArgumentException(
                    "Environment id/key and concurrency 1..32 are required");
        this.sandboxExecutors = sandboxExecutors == null ? this::attach : sandboxExecutors;
        this.base = base;
        this.environment = environment;
        this.key = key;
        results = root.toAbsolutePath().normalize().resolve("results");
        Files.createDirectories(results);
        Path identity = results.getParent().resolve("worker-id");
        if (!Files.exists(identity))
            Files.writeString(
                    identity, "worker_" + UUID.randomUUID(), StandardOpenOption.CREATE_NEW);
        worker = Files.readString(identity).strip();
        slots = new Semaphore(concurrency);
        executions = Executors.newFixedThreadPool(concurrency);
        renewals = Executors.newScheduledThreadPool(concurrency);
    }

    public void run() throws Exception {
        uploads.scheduleWithFixedDelay(this::retryResults, 0, 2, TimeUnit.SECONDS);
        while (running.get()) {
            int free = Math.min(32, slots.availablePermits());
            if (free == 0) {
                Thread.sleep(100);
                continue;
            }
            try {
                var assignments =
                        mapper.convertValue(
                                post(
                                        "tool-tasks",
                                        "claim",
                                        new ManagedWorkerProtocol.Claim(worker, free)),
                                new TypeReference<List<ManagedWorkerProtocol.Assignment>>() {});
                for (var assignment : assignments) {
                    if (!slots.tryAcquire()) break;
                    executions.submit(
                            () -> {
                                try {
                                    execute(assignment);
                                } catch (Exception unconfirmed) {
                                    System.err.println(
                                            "Worker keeps an unconfirmed execution for"
                                                    + " reconciliation: "
                                                    + assignment.task().id()
                                                    + " ("
                                                    + unconfirmed.getClass().getSimpleName()
                                                    + ")");
                                } finally {
                                    slots.release();
                                }
                            });
                }
                if (assignments.isEmpty()) Thread.sleep(2000);
            } catch (Exception unavailable) {
                if (!running.get()) break;
                Thread.sleep(2000);
            }
        }
    }

    void execute(ManagedWorkerProtocol.Assignment assignment) throws Exception {
        var lease = assignment.lease();
        var task = assignment.task();
        var valid = new AtomicBoolean(true);
        var cancel = new AtomicBoolean();
        var deadline = new AtomicLong(System.nanoTime() + Duration.ofSeconds(60).toNanos());
        var thread = Thread.currentThread();
        var heartbeat =
                renewals.scheduleWithFixedDelay(
                        () -> {
                            long before = System.nanoTime();
                            try {
                                var state = post("tool-tasks", "renew", lease);
                                deadline.set(
                                        before
                                                + Duration.ofSeconds(
                                                                state.path("ttl_seconds")
                                                                        .asLong(60))
                                                        .toNanos());
                                if (state.path("cancel_requested").asBoolean()) cancel.set(true);
                                if (deadline.get() - System.nanoTime() <= 0)
                                    throw new IllegalStateException("Renewal expired");
                            } catch (Exception lost) {
                                valid.set(false);
                                thread.interrupt();
                            }
                        },
                        0,
                        15,
                        TimeUnit.SECONDS);
        try {
            long before = System.nanoTime();
            var initial = post("tool-tasks", "renew", lease);
            deadline.set(
                    before + Duration.ofSeconds(initial.path("ttl_seconds").asLong(60)).toNanos());
            if (initial.path("cancel_requested").asBoolean()) cancel.set(true);
            var outputCursor =
                    initial.hasNonNull("output_cursor")
                            ? mapper.treeToValue(
                                    initial.path("output_cursor"), ManagedToolEvents.Cursor.class)
                            : null;
            check(valid, deadline);
            if (assignment.firstLaunch() && cancel.get()) {
                var result =
                        new ManagedWorkerProtocol.Result(
                                lease,
                                "cancelled",
                                ManagedToolResult.cancelledPayload(task.name(), task.arguments()));
                save(result);
                if (outputCursor != null && "replayable".equals(outputCursor.mode()))
                    post(
                            "tool-tasks",
                            "output",
                            new ManagedWorkerProtocol.Output(
                                    lease,
                                    new ManagedToolExecutor.OutputPage(0, 0, "", 0, true, null)));
                upload(result);
                return;
            }
            var executor = sandboxExecutors.open(assignment);
            check(valid, deadline);
            var original =
                    new ToolTask(
                            task.id(),
                            task.turnId(),
                            task.id(),
                            lease.executionId(),
                            task.name(),
                            task.arguments(),
                            0,
                            "starting",
                            task.processHandle(),
                            null,
                            lease.token());
            check(valid, deadline);
            if (assignment.firstLaunch()) {
                executor.start(lease.scope(), original);
                check(valid, deadline);
                post("tool-tasks", "started", lease);
            }
            while (running.get()) {
                check(valid, deadline);
                var outcome = executor.query(lease.scope(), original);
                if (!outcome.terminal() && cancel.get()) {
                    check(valid, deadline);
                    outcome = executor.cancel(lease.scope(), original);
                }
                if (outcome.state() == ManagedToolExecutor.State.NOT_FOUND
                        || outcome.state() == ManagedToolExecutor.State.UNKNOWN) {
                    post("tool-tasks", "unknown", lease);
                    throw new IllegalStateException(
                            "Original external execution is unconfirmed; never restart it");
                }
                ManagedWorkerProtocol.Result confirmed = null;
                if (outcome.terminal()) {
                    confirmed =
                            new ManagedWorkerProtocol.Result(
                                    lease,
                                    outcome.state().name().toLowerCase(Locale.ROOT),
                                    outcome.result());
                    save(confirmed);
                }
                if (outputCursor != null && "replayable".equals(outputCursor.mode())) {
                    do {
                        for (int i = 0; i < 4 && !outputCursor.captureComplete(); i++) {
                            check(valid, deadline);
                            var page =
                                    executor.output(lease.scope(), original, outputCursor.offset());
                            check(valid, deadline);
                            long previous = outputCursor.offset();
                            outputCursor =
                                    mapper.treeToValue(
                                            post(
                                                    "tool-tasks",
                                                    "output",
                                                    new ManagedWorkerProtocol.Output(lease, page)),
                                            ManagedToolEvents.Cursor.class);
                            if (outputCursor.offset() == previous
                                    && !outputCursor.captureComplete()) {
                                Thread.sleep(200);
                                break;
                            }
                        }
                    } while (confirmed != null && !outputCursor.captureComplete());
                }
                if (confirmed != null) {
                    upload(confirmed);
                    return;
                }
                Thread.sleep(200);
            }
        } finally {
            valid.set(false);
            heartbeat.cancel(false);
            Thread.interrupted();
        }
    }

    private ManagedToolExecutor attach(ManagedWorkerProtocol.Assignment assignment)
            throws Exception {
        var config = mapper.valueToTree(assignment.sandboxConfig());
        String sandboxId = config.path("sandboxId").asText();
        if (sandboxId.isBlank())
            throw new IllegalArgumentException("An existing sandbox ID is required");
        var provider = new E2bSandboxClient(ManagedSandboxOptions.from(config), null);
        var sandbox =
                provider.connectExisting(sandboxId, config.path("envdAccessToken").asText(null));
        sandbox.start(); // Borrowed attachment; no recreation, restoring files, or provider
        // deletion.
        ManagedSandboxStaging.stage(sandbox, assignment.definitionFiles(), mapper);
        return new ManagedSandboxToolExecutor(
                mapper,
                command -> {
                    var reply = sandbox.exec(null, command, 30);
                    if (!reply.ok()) throw new IllegalStateException("Sandbox control failed");
                    return reply.stdout();
                });
    }

    private static void check(AtomicBoolean valid, AtomicLong deadline) {
        if (!valid.get()
                || deadline.get() - System.nanoTime() <= 0
                || Thread.currentThread().isInterrupted())
            throw new IllegalStateException("Worker lease lost");
    }

    private void save(ManagedWorkerProtocol.Result result) throws Exception {
        Path file = path(result);
        Path tmp = Files.createTempFile(results, "outcome-", ".tmp");
        try {
            try (var fileChannel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(mapper.writeValueAsBytes(result));
                while (bytes.hasRemaining()) fileChannel.write(bytes);
                fileChannel.force(true);
            }
            Files.move(
                    tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private Path path(ManagedWorkerProtocol.Result result) {
        String task = result.lease().taskId();
        if (!task.matches("[A-Za-z0-9_-]{1,256}"))
            throw new IllegalArgumentException("Invalid Task id");
        return results.resolve(result.lease().scope().messageGroup() + "_" + task + ".json");
    }

    private void upload(ManagedWorkerProtocol.Result result) throws Exception {
        var committed = post("tool-tasks", "result", result);
        if (!"captured".equals(committed.path("status").asText()))
            throw new IllegalStateException("Result not durably captured");
        Files.deleteIfExists(path(result));
    }

    void retryResults() {
        try (var files = Files.list(results)) {
            for (var file :
                    files.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .limit(32)
                            .toList()) {
                try {
                    upload(mapper.readValue(file.toFile(), ManagedWorkerProtocol.Result.class));
                } catch (Exception uncommitted) {
                    /* Durable outcome remains for a later HTTPS retry. */
                }
            }
        } catch (Exception unavailable) {
            /* Retry on the next bounded scan. */
        }
    }

    private JsonNode post(String channel, String operation, Object body) throws Exception {
        String root = base.toString().replaceAll("/+$", "");
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        root
                                                + "/v1/tool-workers/environments/"
                                                + environment
                                                + "/"
                                                + channel
                                                + "/"
                                                + operation))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .header("X-Managed-Agents-Environment-Key", key)
                        .POST(
                                HttpRequest.BodyPublishers.ofByteArray(
                                        mapper.writeValueAsBytes(body)))
                        .build();
        var reply = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var content = reply.body()) {
            if (reply.statusCode() < 200 || reply.statusCode() >= 300)
                throw new IllegalStateException("Tool Worker HTTP " + reply.statusCode());
            byte[] bytes = content.readNBytes(64 * 1024 * 1024 + 1);
            if (bytes.length > 64 * 1024 * 1024)
                throw new IllegalStateException("Tool Worker response exceeds 64 MiB");
            return mapper.readTree(bytes);
        }
    }

    public void close() {
        running.set(false);
        executions.shutdownNow();
        renewals.shutdownNow();
        uploads.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        if (List.of(args).contains("--help")) {
            System.out.println(
                    "Tool Worker: --platform-service for the platform; otherwise --base-url"
                        + " HTTPS_URL --environment-id ID [--tool-worker-root PATH]"
                        + " [--max-concurrency 4]. Set MANAGED_AGENTS_ENVIRONMENT_KEY locally.");
            return;
        }
        String url = option(args, "--base-url", "http://127.0.0.1:18080");
        String environment = option(args, "--environment-id", null);
        String key =
                option(args, "--environment-key", System.getenv("MANAGED_AGENTS_ENVIRONMENT_KEY"));
        Path root =
                Path.of(
                        option(
                                args,
                                "--tool-worker-root",
                                System.getProperty("java.io.tmpdir")
                                        + "/managed-tool-worker-worker"));
        Files.createDirectories(root);
        try (var channel =
                        FileChannel.open(
                                root.resolve("worker.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Worker journal is already used");
            try (var worker =
                    new ManagedEnvironmentWorker(
                            URI.create(url),
                            environment,
                            key,
                            root,
                            Integer.parseInt(option(args, "--max-concurrency", "4")))) {
                Runtime.getRuntime().addShutdownHook(new Thread(worker::close));
                worker.run();
            }
        }
    }

    private static String option(String[] args, String key, String fallback) {
        for (int i = 0; i < args.length - 1; i++) if (key.equals(args[i])) return args[i + 1];
        return fallback;
    }
}
