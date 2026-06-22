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
package io.agentscope.core.responses.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Generic OpenAI-style list wrapper used by Responses and Conversations resources. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesList<T> {

    private String object = "list";
    private List<T> data;

    @JsonProperty("first_id")
    private String firstId;

    @JsonProperty("last_id")
    private String lastId;

    @JsonProperty("has_more")
    private boolean hasMore;

    public ResponsesList() {}

    /**
     * Create a list wrapper without additional pages.
     *
     * @param data Page data
     */
    public ResponsesList(List<T> data) {
        this(data, false);
    }

    /**
     * Create a list wrapper and derive cursor IDs from the first and last items.
     *
     * @param data Page data
     * @param hasMore Whether another page is available
     */
    public ResponsesList(List<T> data, boolean hasMore) {
        this.data = data;
        this.firstId = id(data, 0);
        this.lastId = id(data, data != null ? data.size() - 1 : -1);
        this.hasMore = hasMore;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public List<T> getData() {
        return data;
    }

    public void setData(List<T> data) {
        this.data = data;
        this.firstId = id(data, 0);
        this.lastId = id(data, data != null ? data.size() - 1 : -1);
    }

    public String getFirstId() {
        return firstId;
    }

    public void setFirstId(String firstId) {
        this.firstId = firstId;
    }

    public String getLastId() {
        return lastId;
    }

    public void setLastId(String lastId) {
        this.lastId = lastId;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }

    private String id(List<T> values, int index) {
        if (values == null || index < 0 || index >= values.size()) {
            return null;
        }
        Object value = values.get(index);
        if (value instanceof Map<?, ?> map) {
            Object id = map.get("id");
            return id instanceof String text && !text.isBlank() ? text : null;
        }
        try {
            // DTO pages expose getId(); map pages use an explicit "id" key. Supporting both keeps
            // Responses and Conversations resources on one generic list wrapper.
            Object id = value.getClass().getMethod("getId").invoke(value);
            return id instanceof String text && !text.isBlank() ? text : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
