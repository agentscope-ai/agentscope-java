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
package io.agentscope.harness.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TestCleanupSupport {

    private TestCleanupSupport() {}

    public static <T extends AutoCloseable> T track(List<AutoCloseable> closeables, T closeable) {
        if (closeable != null) {
            closeables.add(closeable);
        }
        return closeable;
    }

    public static void closeAll(List<AutoCloseable> closeables) {
        RuntimeException failure = null;
        List<AutoCloseable> snapshot = new ArrayList<>(closeables);
        closeables.clear();
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            AutoCloseable closeable = snapshot.get(i);
            if (closeable == null) {
                continue;
            }
            try {
                closeable.close();
            } catch (Exception e) {
                RuntimeException wrapped =
                        new RuntimeException(
                                "Failed to close test resource " + closeable.getClass().getName(),
                                e);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public static void deleteRecursivelyWithRetry(Path root) {
        if (root == null) {
            return;
        }
        IOException lastError = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                if (!Files.exists(root)) {
                    return;
                }
                try (var paths = Files.walk(root)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(
                                    path -> {
                                        try {
                                            Files.deleteIfExists(path);
                                        } catch (IOException e) {
                                            throw new UncheckedIOException(e);
                                        }
                                    });
                }
                return;
            } catch (UncheckedIOException e) {
                lastError = e.getCause();
            } catch (IOException e) {
                lastError = e;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while deleting temp directory " + root, e);
            }
        }
        throw new RuntimeException("Failed to delete temp directory " + root, lastError);
    }
}
