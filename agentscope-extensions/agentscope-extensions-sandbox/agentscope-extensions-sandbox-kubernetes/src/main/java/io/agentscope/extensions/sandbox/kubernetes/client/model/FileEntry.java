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
package io.agentscope.extensions.sandbox.kubernetes.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A file or directory entry returned by the sandbox filesystem list API. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileEntry {

    @JsonProperty("name")
    private String name;

    @JsonProperty("size")
    private long size;

    /** Either {@code file} or {@code directory}. */
    @JsonProperty("type")
    private String type;

    /** Last modification time as a POSIX timestamp. */
    @JsonProperty("mod_time")
    private double modTime;

    public FileEntry() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public double getModTime() {
        return modTime;
    }

    public void setModTime(double modTime) {
        this.modTime = modTime;
    }
}
