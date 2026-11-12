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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.BucketVersioningConfiguration;
import com.aliyun.oss.model.PutObjectRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class RuntimeObjectStoreTest {
    @TempDir Path directory;

    @Test
    void concurrentCreationAndSecondReplicaCannotOverwriteBytes() throws Exception {
        var first = new FileRuntimeObjectStore("shared", directory);
        var second = new FileRuntimeObjectStore("shared", directory);
        byte[] content = new byte[] {1, 2, 3};
        var tasks = new ArrayList<Callable<RuntimeObjectStore.Reference>>();
        for (int i = 0; i < 16; i++) tasks.add(() -> first.put("scope", "native", "key", content));
        var pool = Executors.newFixedThreadPool(4);
        try {
            for (var future : pool.invokeAll(tasks))
                assertArrayEquals(content, second.read(future.get()));
        } finally {
            pool.shutdownNow();
        }
        assertThrows(
                ManagedStateException.class,
                () -> second.put("scope", "native", "key", new byte[] {4}));
        assertArrayEquals(content, first.read("scope", "native", "key"));
    }

    @Test
    void fixedReferencesCheckBackendHashSizeAndScopePath() throws Exception {
        var store = new FileRuntimeObjectStore("shared", directory);
        var ref = store.put("scope", "checkpoint", "key", new byte[] {1});
        assertThrows(
                ManagedStateException.class,
                () ->
                        store.read(
                                new RuntimeObjectStore.Reference(
                                        "other",
                                        ref.scope(),
                                        ref.kind(),
                                        ref.key(),
                                        ref.sha256(),
                                        ref.size())));
        Files.write(directory.resolve("scope/checkpoint/key"), new byte[] {2});
        assertThrows(ManagedStateException.class, () -> store.read(ref));
        assertThrows(
                IllegalArgumentException.class, () -> store.read("../outside", "native", "key"));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put("scope", "native", "../key", new byte[] {1}));
        assertThrows(
                ManagedTransportException.class, () -> store.read("scope", "native", "missing"));
    }

    @Test
    void namespaceSymlinksAreRejected() throws Exception {
        var outside = Files.createDirectory(directory.resolve("outside"));
        var root = Files.createDirectory(directory.resolve("root"));
        Files.createSymbolicLink(root.resolve("scope"), outside);
        var store = new FileRuntimeObjectStore(root);
        assertThrows(
                ManagedStateException.class,
                () -> store.put("scope", "state", "key", new byte[] {1}));
        try (var paths = Files.list(outside)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void ossSendsForbidOverwriteAndRejectsVersionedBucket() {
        OSS client = mock(OSS.class);
        when(client.getBucketVersioning("bucket")).thenReturn(new BucketVersioningConfiguration());
        try (var store = new OssRuntimeObjectStore("oss", "bucket", client)) {
            store.put("scope", "state", "key", new byte[] {1});
            var request = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(client).putObject(request.capture());
            assertEquals(
                    "true",
                    request.getValue()
                            .getMetadata()
                            .getRawMetadata()
                            .get("x-oss-forbid-overwrite"));
        }
        when(client.getBucketVersioning("bucket"))
                .thenReturn(new BucketVersioningConfiguration("Enabled"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OssRuntimeObjectStore("oss", "bucket", client));
    }
}
