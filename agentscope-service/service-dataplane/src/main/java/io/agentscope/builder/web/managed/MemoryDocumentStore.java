package io.agentscope.builder.web.managed;

import java.util.List;

/** Live document access, scoped to one authorized session mount. */
public interface MemoryDocumentStore {
    List<MemoryDto> list();

    MemoryDto get(String path);

    void put(String path, String content, Integer expectedVersion);

    void delete(String path);
}
