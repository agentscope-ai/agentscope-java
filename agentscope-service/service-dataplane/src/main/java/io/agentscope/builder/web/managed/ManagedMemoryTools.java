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
package io.agentscope.builder.web.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Direct CP memory operations also usable when filesystem tools execute on an external Worker. */
public final class ManagedMemoryTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, MemoryStoreFilesystem> stores;

    public ManagedMemoryTools(List<MemoryStoreFilesystem> stores) {
        this.stores =
                stores.stream().collect(Collectors.toMap(MemoryStoreFilesystem::storeId, s -> s));
    }

    private MemoryStoreFilesystem store(String id) {
        MemoryStoreFilesystem store = stores.get(id);
        if (store == null) throw new IllegalArgumentException("Memory store is not mounted");
        return store;
    }

    private String json(Object result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode memory result", e);
        }
    }

    @Tool(
            name = "memory_store_list",
            readOnly = true,
            description =
                    "List documents in a session-bound persistent memory store. Uses live platform"
                            + " storage in all environments.")
    public String list(
            @ToolParam(name = "storeId", description = "Mounted store ID from the system prompt")
                    String id) {
        return json(store(id).ls(null, ""));
    }

    @Tool(
            name = "memory_store_read",
            readOnly = true,
            description = "Read a document from a session-bound persistent memory store.")
    public String read(
            @ToolParam(name = "storeId", description = "Mounted store ID") String id,
            @ToolParam(name = "path", description = "Document path within the store") String path) {
        return json(store(id).read(null, path, 0, 0));
    }

    @Tool(
            name = "memory_store_write",
            description =
                    "Create a new persistent memory document. Existing documents must be edited."
                            + " Read-only mounts reject writes.")
    public String write(
            @ToolParam(name = "storeId", description = "Mounted store ID") String id,
            @ToolParam(name = "path", description = "Document path within the store") String path,
            @ToolParam(name = "content", description = "Document text") String content) {
        return json(store(id).write(null, path, content));
    }

    @Tool(
            name = "memory_store_edit",
            description =
                    "Replace a unique exact text match in a persistent memory document. Concurrent"
                            + " modifications fail and require rereading.")
    public String edit(
            @ToolParam(name = "storeId", description = "Mounted store ID") String id,
            @ToolParam(name = "path", description = "Document path within the store") String path,
            @ToolParam(name = "oldText", description = "Unique text to replace") String oldText,
            @ToolParam(name = "newText", description = "Replacement text") String newText) {
        return json(store(id).edit(null, path, oldText, newText, false));
    }
}
