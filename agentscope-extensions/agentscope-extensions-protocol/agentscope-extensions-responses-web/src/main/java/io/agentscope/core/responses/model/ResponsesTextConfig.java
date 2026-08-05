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

/** Text output configuration for Responses requests and responses. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesTextConfig {

    private Format format;

    public Format getFormat() {
        return format;
    }

    public void setFormat(Format format) {
        this.format = format;
    }

    /** Response text format definition. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Format {

        private String type;
        private String name;
        private String description;
        private Object schema;
        private Boolean strict;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Object getSchema() {
            return schema;
        }

        public void setSchema(Object schema) {
            this.schema = schema;
        }

        public Boolean getStrict() {
            return strict;
        }

        public void setStrict(Boolean strict) {
            this.strict = strict;
        }
    }
}
