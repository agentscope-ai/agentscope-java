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

package io.agentscope.builder.web.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.web.managed.service.MemoryStoreService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryMountServiceTest {
    @Test
    void resolvedMountReadsLiveContentAndKeepsSharedKnowledgeReadOnly() {
        var client = mock(ControlPlaneClient.class);
        var documents = mock(MemoryDocumentStore.class);
        when(client.memoryDocuments("session-one", "knowledge")).thenReturn(documents);
        when(documents.get("marker.md"))
                .thenReturn(
                        new MemoryDto(
                                "doc", "knowledge", "marker.md", "acceptance-marker-v1", 1, 0, 0))
                .thenReturn(
                        new MemoryDto(
                                "doc", "knowledge", "marker.md", "acceptance-marker-v2", 2, 0, 0));
        var service = new MemoryMountService(mock(MemoryStoreService.class));
        var mounts =
                service.createResolvedFilesystems(
                        client,
                        "session-one",
                        "alice",
                        List.of(Map.of("storeId", "knowledge", "name", "Shared knowledge")),
                        Map.of("knowledge", "read_only"));
        assertThat(mounts).hasSize(1);
        var filesystem = mounts.get(0);
        assertThat(filesystem.read(null, "marker.md", 0, 0).fileData().content())
                .isEqualTo("acceptance-marker-v1");
        assertThat(filesystem.read(null, "marker.md", 0, 0).fileData().content())
                .isEqualTo("acceptance-marker-v2");
        assertThat(filesystem.write(null, "marker.md", "private working note").isSuccess())
                .isFalse();
        String instructions =
                service.promptAppendix(
                        List.of(
                                new MemoryMountService.MountInfo(
                                        filesystem.storeId(), filesystem.storeName(),
                                        MemoryStoreFilesystem.routePrefix(filesystem.storeName()),
                                                filesystem.accessMode())));
        assertThat(instructions)
                .contains("access=read_only", "private working notes")
                .doesNotContain("Read and update");
    }

    @Test
    void unboundSessionHasNoSharedMemoryMountOrClientAccess() {
        var client = mock(ControlPlaneClient.class);
        var service = new MemoryMountService(mock(MemoryStoreService.class));
        assertThat(
                        service.createResolvedFilesystems(
                                client, "session-two", "alice", List.of(), Map.of()))
                .isEmpty();
        assertThat(service.promptAppendix(List.of())).isNull();
        verifyNoInteractions(client);
    }
}
