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
package io.agentscope.core.memory.mem0;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Response object from Mem0's add memory API.
 *
 * <p>This response is returned from the {@code POST /v1/memories} endpoint after
 * successfully adding memories. It contains the extracted memories and any relevant
 * metadata about the operation.
 *
 * <p>The {@code results} field contains the memories that were successfully extracted
 * and stored. An empty results list indicates that no memorable information was found
 * in the input messages.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Mem0AddResponse {

    /**
     * List of memory results, each containing the extracted memory and metadata.
     * May be empty if no memories were extracted.
     */
    private List<Map<String, Object>> results;

    /** Optional message about the operation result. */
    private String message;

    /** Default constructor for Jackson. */
    public Mem0AddResponse() {}

    // Getters and Setters

    public List<Map<String, Object>> getResults() {
        return results;
    }

    public void setResults(List<Map<String, Object>> results) {
        this.results = results;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "Mem0AddResponse{"
                + "results="
                + (results != null ? results.size() + " items" : "null")
                + ", message='"
                + message
                + '\''
                + '}';
    }
}
