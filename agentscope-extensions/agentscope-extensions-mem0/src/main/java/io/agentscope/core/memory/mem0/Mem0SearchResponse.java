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

/**
 * Response object from Mem0's search memory API.
 *
 * <p>This response is returned from the {@code POST /v1/memories/search} endpoint
 * after performing a semantic search. It contains a list of memory results ordered
 * by relevance (highest score first).
 *
 * <p>An empty results list indicates that no relevant memories were found for the query.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Mem0SearchResponse {

    /**
     * List of search results, ordered by relevance score (descending).
     * May be empty if no relevant memories are found.
     */
    private List<Mem0SearchResult> results;

    /** Default constructor for Jackson. */
    public Mem0SearchResponse() {}

    // Getters and Setters

    public List<Mem0SearchResult> getResults() {
        return results;
    }

    public void setResults(List<Mem0SearchResult> results) {
        this.results = results;
    }

    @Override
    public String toString() {
        return "Mem0SearchResponse{"
                + "results="
                + (results != null ? results.size() + " items" : "null")
                + '}';
    }
}
