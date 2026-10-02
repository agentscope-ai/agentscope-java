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
package io.agentscope.core.memory.bailian;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response object from Bailian Memory API's add memory operation.
 *
 * <p>This response is returned from the POST /api/v2/apps/memory/add endpoint
 * after successfully adding memories. It contains the extracted memories and
 * the request ID.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BailianAddResponse {

    /** Request ID for tracing. */
    @JsonProperty("request_id")
    private String requestId;

    /** List of memory nodes that were created or updated. */
    @JsonProperty("memory_nodes")
    private List<BailianMemoryNode> memoryNodes;

    /** Default constructor for Jackson. */
    public BailianAddResponse() {}

    /**
     * Gets the request ID.
     *
     * @return the request ID
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Sets the request ID.
     *
     * @param requestId the request ID
     */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /**
     * Gets the memory nodes.
     *
     * @return the memory nodes list
     */
    public List<BailianMemoryNode> getMemoryNodes() {
        return memoryNodes;
    }

    /**
     * Sets the memory nodes.
     *
     * @param memoryNodes the memory nodes list
     */
    public void setMemoryNodes(List<BailianMemoryNode> memoryNodes) {
        this.memoryNodes = memoryNodes;
    }

    /**
     * Represents a memory node in the response.
     */
    public static class BailianMemoryNode {

        /** Unique identifier for the memory node. */
        @JsonProperty("memory_node_id")
        private String memoryNodeId;

        /** The memory content. */
        private String content;

        /** Event type (ADD, UPDATE, DELETE). */
        private String event;

        /** Previous content (only for UPDATE events). */
        @JsonProperty("old_content")
        private String oldContent;

        /** Default constructor for Jackson. */
        public BailianMemoryNode() {}

        /**
         * Gets the memory node ID.
         *
         * @return the memory node ID
         */
        public String getMemoryNodeId() {
            return memoryNodeId;
        }

        /**
         * Sets the memory node ID.
         *
         * @param memoryNodeId the memory node ID
         */
        public void setMemoryNodeId(String memoryNodeId) {
            this.memoryNodeId = memoryNodeId;
        }

        /**
         * Gets the content.
         *
         * @return the content
         */
        public String getContent() {
            return content;
        }

        /**
         * Sets the content.
         *
         * @param content the content
         */
        public void setContent(String content) {
            this.content = content;
        }

        /**
         * Gets the event type.
         *
         * @return the event type
         */
        public String getEvent() {
            return event;
        }

        /**
         * Sets the event type.
         *
         * @param event the event type
         */
        public void setEvent(String event) {
            this.event = event;
        }

        /**
         * Gets the old content.
         *
         * @return the old content
         */
        public String getOldContent() {
            return oldContent;
        }

        /**
         * Sets the old content.
         *
         * @param oldContent the old content
         */
        public void setOldContent(String oldContent) {
            this.oldContent = oldContent;
        }
    }
}
