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
package io.agentcore.managed.server.web.managed.selfhosted;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stages session metadata / resource file references into a worker workspace.
 *
 * <p>Supports Claude-style {@code input_file} / {@code files} metadata keys as well as Builder
 * session {@code resources[]} entries with {@code type=file} (url / path / file://).
 */
public final class SessionInputStager {

    private SessionInputStager() {}

    /**
     * Builds a worker-facing metadata map from a session's {@code resources[]} list so poll
     * responses can carry staging hints without a second round-trip.
     */
    public static Map<String, Object> metadataFromResources(List<Map<String, Object>> resources) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (resources == null || resources.isEmpty()) {
            return metadata;
        }
        metadata.put("resources", resources);
        List<String> files = new ArrayList<>();
        for (Map<String, Object> resource : resources) {
            if (resource == null) {
                continue;
            }
            String type = stringOf(resource.get("type"));
            if ("file".equals(type) || type == null) {
                String ref =
                        firstNonBlank(
                                resource.get("url"),
                                resource.get("path"),
                                resource.get("uri"),
                                resource.get("file"),
                                resource.get("input_file"));
                if (ref != null) {
                    files.add(ref);
                }
            }
            String inputFile = stringOf(resource.get("input_file"));
            if (inputFile != null && !metadata.containsKey("input_file")) {
                metadata.put("input_file", inputFile);
            }
        }
        if (!files.isEmpty()) {
            metadata.put("files", files);
        }
        return metadata;
    }

    /** Stages {@code input_file} / {@code files} / {@code resources} references under workDir. */
    public static void stage(Map<String, Object> metadata, Path workDir) throws Exception {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        var budget = new StagingBudget();
        Object inputFile = metadata.get("input_file");
        if (inputFile != null) {
            stageOneFile(String.valueOf(inputFile), workDir, budget);
        }
        Object files = metadata.get("files");
        if (files instanceof Iterable<?> iterable) {
            for (Object f : iterable) {
                if (f != null) {
                    stageOneFile(String.valueOf(f), workDir, budget);
                }
            }
        }
        Object resources = metadata.get("resources");
        if (resources instanceof Iterable<?> iterable) {
            for (Object r : iterable) {
                if (r instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resource = (Map<String, Object>) map;
                    String type = stringOf(resource.get("type"));
                    if ("file".equals(type) || type == null) {
                        String ref =
                                firstNonBlank(
                                        resource.get("url"),
                                        resource.get("path"),
                                        resource.get("uri"),
                                        resource.get("file"),
                                        resource.get("input_file"));
                        if (ref != null) {
                            stageOneFile(ref, workDir, budget);
                        }
                    }
                }
            }
        }
    }

    private static void stageOneFile(String ref, Path workDir, StagingBudget budget)
            throws Exception {
        if (ref == null || ref.isBlank()) {
            return;
        }
        if (!budget.references.add(ref)) return;
        if (budget.references.size() > 128)
            throw new IOException("Input staging exceeds 128 references");
        budget.check(0);
        Path destDir = workDir.resolve("inputs");
        Files.createDirectories(destDir);
        if (ref.startsWith("file://")) {
            Path src = Path.of(URI.create(ref));
            if (Files.size(src) > 32L * 1024 * 1024)
                throw new IOException("Input file exceeds 32 MiB staging limit");
            budget.check(Files.size(src));
            Path dest = destDir.resolve(src.getFileName().toString());
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            HttpClient client =
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var response =
                    client.send(
                            HttpRequest.newBuilder(URI.create(ref))
                                    .timeout(Duration.ofSeconds(30))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() >= 300) throw new IOException("Input download rejected");
                var rawName = Path.of(URI.create(ref).getPath()).getFileName();
                String name =
                        rawName == null || rawName.toString().isBlank()
                                ? "download.bin"
                                : rawName.toString();
                Path temporary = Files.createTempFile(destDir, ".input-", ".tmp");
                try (var output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[16384];
                    int count;
                    long total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        budget.check(count);
                        total += count;
                        if (total > 32L * 1024 * 1024)
                            throw new IOException("Input exceeds 32 MiB staging limit");
                        output.write(buffer, 0, count);
                    }
                } catch (Exception failure) {
                    Files.deleteIfExists(temporary);
                    throw failure;
                }
                Files.move(temporary, destDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        Path maybe = Path.of(ref);
        if (Files.exists(maybe)) {
            if (Files.size(maybe) > 32L * 1024 * 1024)
                throw new IOException("Input file exceeds 32 MiB staging limit");
            budget.check(Files.size(maybe));
            Files.copy(
                    maybe,
                    destDir.resolve(maybe.getFileName().toString()),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class StagingBudget {
        final Set<String> references = new HashSet<>();
        final long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        long bytes;

        void check(long count) throws IOException {
            bytes += count;
            if (bytes > 64L * 1024 * 1024)
                throw new IOException("Input staging exceeds 64 MiB total limit");
            if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted())
                throw new IOException("Input staging deadline exceeded");
        }
    }

    private static String firstNonBlank(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String s = stringOf(value);
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
