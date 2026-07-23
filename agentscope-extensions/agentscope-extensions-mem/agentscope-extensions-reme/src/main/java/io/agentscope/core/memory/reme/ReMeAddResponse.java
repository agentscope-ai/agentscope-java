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
package io.agentscope.core.memory.reme;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Response object from ReMe's {@code auto_memory} job.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReMeAddResponse {

    /** The answer text (usually empty for add operations). */
    private String answer;

    /** Whether the operation was successful. */
    private Boolean success;

    /** Metadata containing additional information. */
    private Metadata metadata;

    /** Default constructor for Jackson. */
    public ReMeAddResponse() {}

    // Getters and Setters

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    @Override
    public String toString() {
        return "ReMeAddResponse{"
                + "answer='"
                + answer
                + '\''
                + ", success="
                + success
                + ", metadata="
                + metadata
                + '}';
    }

    /** Metadata returned by ReMe's job runtime. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Metadata {
        private String path;

        private Boolean created;

        private Boolean modified;

        @JsonProperty("n_messages")
        private Integer nMessages;

        @JsonProperty("source_conversation")
        private String sourceConversation;

        private Map<String, Object> index;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Boolean getCreated() {
            return created;
        }

        public void setCreated(Boolean created) {
            this.created = created;
        }

        public Boolean getModified() {
            return modified;
        }

        public void setModified(Boolean modified) {
            this.modified = modified;
        }

        public Integer getNMessages() {
            return nMessages;
        }

        public void setNMessages(Integer nMessages) {
            this.nMessages = nMessages;
        }

        public String getSourceConversation() {
            return sourceConversation;
        }

        public void setSourceConversation(String sourceConversation) {
            this.sourceConversation = sourceConversation;
        }

        public Map<String, Object> getIndex() {
            return index;
        }

        public void setIndex(Map<String, Object> index) {
            this.index = index;
        }

        @Override
        public String toString() {
            return "Metadata{"
                    + "path='"
                    + path
                    + '\''
                    + ", created="
                    + created
                    + ", modified="
                    + modified
                    + ", nMessages="
                    + nMessages
                    + '}';
        }
    }
}
