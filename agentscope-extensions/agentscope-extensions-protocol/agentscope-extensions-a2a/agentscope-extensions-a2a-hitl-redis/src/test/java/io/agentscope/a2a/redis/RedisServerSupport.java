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
package io.agentscope.a2a.redis;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/** Disposable native Redis process used by integration tests without shared infrastructure. */
final class RedisServerSupport implements AutoCloseable {

    private final Process process;
    private final RedissonClient client;
    private final Path directory;
    private final Path log;
    private final String uri;

    private RedisServerSupport(
            Process process, RedissonClient client, Path directory, Path log, String uri) {
        this.process = process;
        this.client = client;
        this.directory = directory;
        this.log = log;
        this.uri = uri;
    }

    static RedisServerSupport start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(false);
            port = socket.getLocalPort();
        }
        String executable = System.getenv().getOrDefault("REDIS_SERVER_BIN", "redis-server");
        Path directory = Files.createTempDirectory("agentscope-a2a-redis-");
        Path log = directory.resolve("redis.log");
        Process process;
        try {
            process =
                    new ProcessBuilder(
                                    executable,
                                    "--bind",
                                    "127.0.0.1",
                                    "--protected-mode",
                                    "yes",
                                    "--port",
                                    String.valueOf(port),
                                    "--dir",
                                    directory.toString(),
                                    "--save",
                                    "",
                                    "--appendonly",
                                    "no")
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile())
                            .start();
        } catch (IOException unavailable) {
            deleteDirectory(directory);
            if (isWindows()) {
                Assumptions.abort("native redis-server is unavailable on Windows");
            }
            throw new IOException(
                    "native redis-server is required on Linux/macOS; set REDIS_SERVER_BIN",
                    unavailable);
        }

        String uri = "redis://127.0.0.1:" + port;
        RedissonClient client = null;
        try {
            Config config = new Config();
            config.useSingleServer()
                    .setAddress(uri)
                    .setConnectionMinimumIdleSize(1)
                    .setConnectionPoolSize(4);
            client = Redisson.create(config);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (true) {
                try {
                    if (!process.isAlive()) {
                        throw new IOException(
                                "redis-server exited before readiness; log="
                                        + Files.readString(log));
                    }
                    client.getBucket("agentscope:a2a:test:ready", StringCodec.INSTANCE).set("ok");
                    client.getBucket("agentscope:a2a:test:ready", StringCodec.INSTANCE).delete();
                    break;
                } catch (RuntimeException unavailable) {
                    if (System.nanoTime() >= deadline) {
                        throw new IOException(
                                "redis-server did not become ready; log=" + log, unavailable);
                    }
                    Thread.sleep(50);
                }
            }
            return new RedisServerSupport(process, client, directory, log, uri);
        } catch (Exception failure) {
            if (client != null && !client.isShutdown()) {
                client.shutdown();
            }
            stopProcess(process);
            deleteDirectory(directory);
            throw failure;
        }
    }

    RedissonClient client() {
        return client;
    }

    String uri() {
        return uri;
    }

    boolean processAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        if (!client.isShutdown()) {
            client.shutdown();
        }
        stopProcess(process);
        deleteDirectory(directory);
    }

    private static void stopProcess(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void deleteDirectory(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // Best-effort cleanup after the process has been terminated.
                                }
                            });
        } catch (IOException ignored) {
            // Best-effort cleanup after the process has been terminated.
        }
    }
}
