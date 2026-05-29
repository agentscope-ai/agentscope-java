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

/** Deletion confirmation payload for stateful Responses and Conversations resources. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesDeletionStatus {

    private String id;
    private String object;
    private boolean deleted;

    public ResponsesDeletionStatus() {}

    public ResponsesDeletionStatus(String id, String object, boolean deleted) {
        this.id = id;
        this.object = object;
        this.deleted = deleted;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
