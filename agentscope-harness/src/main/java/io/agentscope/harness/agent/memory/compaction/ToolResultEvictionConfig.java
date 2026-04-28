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
package io.agentscope.harness.agent.memory.compaction;

import java.util.Set;

/**
 * Configuration for per-tool-result eviction of oversized outputs.
 *
 * <p>When a tool produces a result whose text content exceeds {@link #getMaxResultChars()}, the
 * full output is written to the workspace filesystem abstraction at a deterministic path under
 * {@link #getEvictionPath()}, and the in-context {@link io.agentscope.core.message.ToolResultBlock}
 * is replaced with a compact placeholder that includes a head+tail preview and an instruction to
 * use {@code readFile} for the full content.
 *
 * <p>This mechanism is <b>orthogonal</b> to conversation summarization ({@link CompactionConfig}):
 * <ul>
 *   <li><b>Eviction</b> addresses context <em>width</em> — individual messages that are too large.</li>
 *   <li><b>Compaction</b> addresses context <em>depth</em> — too many accumulated messages.</li>
 * </ul>
 * Both operate independently on different trigger conditions and different lifecycle events.
 *
 * <ul>
 *   <li>Trigger at 80,000 characters (~20 K tokens at 4 chars/token)</li>
 *   <li>Preview: first + last 2,000 characters of the original output</li>
 *   <li>Eviction path prefix: {@code /large_tool_results}</li>
 *   <li>Excluded tools: filesystem read/write/edit/list + memory tools (small or self-paginating)</li>
 * </ul>
 */
public class ToolResultEvictionConfig {

    /** ~20 K tokens × 4 chars/token — default eviction threshold. */
    public static final int DEFAULT_MAX_RESULT_CHARS = 80_000;

    /** Characters to show at head and tail in the eviction placeholder preview. */
    public static final int DEFAULT_PREVIEW_CHARS = 2_000;

    /** Root path prefix under which evicted results are stored. */
    public static final String DEFAULT_EVICTION_PATH = "/large_tool_results";

    /**
     * Tools excluded from eviction by default.
     *
     * <ul>
     *   <li>{@code read_file} — evicting would cause re-read loops; pagination handles size</li>
     *   <li>{@code write_file}, {@code edit_file} — return tiny success messages</li>
     *   <li>{@code grep_files}, {@code glob_files}, {@code list_files} — self-limiting outputs</li>
     *   <li>{@code memory_search}, {@code memory_get}, {@code session_search} — small/paginated results</li>
     * </ul>
     *
     * Shell ({@code execute}) is intentionally NOT excluded: command output can be very large.
     */
    public static final Set<String> DEFAULT_EXCLUDED_TOOLS =
            Set.of(
                    "read_file",
                    "write_file",
                    "edit_file",
                    "grep_files",
                    "glob_files",
                    "list_files",
                    "memory_search",
                    "memory_get",
                    "session_search");

    private final int maxResultChars;
    private final int previewChars;
    private final String evictionPath;
    private final Set<String> excludedToolNames;

    private ToolResultEvictionConfig(Builder builder) {
        this.maxResultChars = builder.maxResultChars;
        this.previewChars = builder.previewChars;
        this.evictionPath = builder.evictionPath;
        this.excludedToolNames = builder.excludedToolNames;
    }

    /** Creates a config with all defaults applied. */
    public static ToolResultEvictionConfig defaults() {
        return new Builder().build();
    }

    /** Maximum text length (chars) before eviction fires. */
    public int getMaxResultChars() {
        return maxResultChars;
    }

    /** Characters to show in the head and tail preview. */
    public int getPreviewChars() {
        return previewChars;
    }

    /** Root path under which evicted files are written (e.g. {@code /large_tool_results}). */
    public String getEvictionPath() {
        return evictionPath;
    }

    /** Tool names that will never be evicted regardless of result size. */
    public Set<String> getExcludedToolNames() {
        return excludedToolNames;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link ToolResultEvictionConfig}. */
    public static class Builder {

        private int maxResultChars = DEFAULT_MAX_RESULT_CHARS;
        private int previewChars = DEFAULT_PREVIEW_CHARS;
        private String evictionPath = DEFAULT_EVICTION_PATH;
        private Set<String> excludedToolNames = DEFAULT_EXCLUDED_TOOLS;

        /** Sets the character threshold above which eviction is triggered. */
        public Builder maxResultChars(int maxResultChars) {
            this.maxResultChars = maxResultChars;
            return this;
        }

        /** Sets how many characters to include in the head/tail preview. */
        public Builder previewChars(int previewChars) {
            this.previewChars = previewChars;
            return this;
        }

        /** Sets the root filesystem path prefix for evicted files. */
        public Builder evictionPath(String evictionPath) {
            this.evictionPath = evictionPath;
            return this;
        }

        /** Replaces the default set of excluded tool names. */
        public Builder excludedToolNames(Set<String> excludedToolNames) {
            this.excludedToolNames = Set.copyOf(excludedToolNames);
            return this;
        }

        public ToolResultEvictionConfig build() {
            return new ToolResultEvictionConfig(this);
        }
    }
}
