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
package io.agentscope.core.tool;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.HashMap;
import java.util.Map;

/** Shared, reference-counted ownership of MCP client transports across toolkit copies. */
final class McpClientLifecycleOwner {

    private final Map<String, Entry> entries = new HashMap<>();

    synchronized void register(String name, McpClientWrapper wrapper) {
        if (entries.containsKey(name)) {
            throw new IllegalStateException("MCP client already registered: " + name);
        }
        entries.put(name, new Entry(wrapper, 1));
    }

    synchronized McpClientWrapper acquire(String name) {
        Entry entry = entries.get(name);
        if (entry == null) {
            throw new IllegalStateException("MCP client lifecycle owner not found: " + name);
        }
        entry.references++;
        return entry.wrapper;
    }

    McpClientWrapper release(String name) {
        McpClientWrapper toClose = null;
        synchronized (this) {
            Entry entry = entries.get(name);
            if (entry == null) {
                return null;
            }
            entry.references--;
            if (entry.references == 0) {
                entries.remove(name);
                toClose = entry.wrapper;
            }
        }
        if (toClose != null) {
            toClose.close();
        }
        return toClose;
    }

    synchronized McpClientWrapper get(String name) {
        Entry entry = entries.get(name);
        return entry != null ? entry.wrapper : null;
    }

    private static final class Entry {
        private final McpClientWrapper wrapper;
        private int references;

        private Entry(McpClientWrapper wrapper, int references) {
            this.wrapper = wrapper;
            this.references = references;
        }
    }
}
