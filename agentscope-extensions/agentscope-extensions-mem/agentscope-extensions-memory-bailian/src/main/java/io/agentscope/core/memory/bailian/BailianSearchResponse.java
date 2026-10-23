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
 * Response object from Bailian Memory API's search memory operation.
 *
 * <p>This response is returned from the POST /api/v2/apps/memory/memory_nodes/search endpoint
 * after performing a semantic search. It contains a list of memory nodes ordered by
 * relevance.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BailianSearchResponse {

    /** Request ID for tracing. */
    @JsonProperty("request_id")
    private String requestId;

    /** List of memory nodes, ordered by relevance. */
    @JsonProperty("memory_nodes")
    private List<BailianMemoryNode> memoryNodes;

    /** Default constructor for Jackson. */
    public BailianSearchResponse() {}

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
}
