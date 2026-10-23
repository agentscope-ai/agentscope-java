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
import java.util.List;
import java.util.Map;

/**
 * Response object from ReMe's add memory API.
 *
 * <p>This response is returned from the {@code POST /summary_personal_memory} endpoint
 * after successfully adding memories. The actual response format is:
 * <pre>{@code
 * {
 *   "answer": "",
 *   "success": true,
 *   "metadata": {
 *     "memory_list": [...],
 *     "deleted_memory_ids": [],
 *     "update_result": {
 *       "deleted_count": 0,
 *       "inserted_count": 1
 *     }
 *   }
 * }
 * }</pre>
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

    /** Metadata object containing memory list and update results. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Metadata {
        @JsonProperty("memory_list")
        private List<MemoryItem> memoryList;

        @JsonProperty("deleted_memory_ids")
        private List<String> deletedMemoryIds;

        @JsonProperty("update_result")
        private UpdateResult updateResult;

        public List<MemoryItem> getMemoryList() {
            return memoryList;
        }

        public void setMemoryList(List<MemoryItem> memoryList) {
            this.memoryList = memoryList;
        }

        public List<String> getDeletedMemoryIds() {
            return deletedMemoryIds;
        }

        public void setDeletedMemoryIds(List<String> deletedMemoryIds) {
            this.deletedMemoryIds = deletedMemoryIds;
        }

        public UpdateResult getUpdateResult() {
            return updateResult;
        }

        public void setUpdateResult(UpdateResult updateResult) {
            this.updateResult = updateResult;
        }

        @Override
        public String toString() {
            return "Metadata{"
                    + "memoryList="
                    + (memoryList != null ? memoryList.size() + " items" : "null")
                    + ", deletedMemoryIds="
                    + (deletedMemoryIds != null ? deletedMemoryIds.size() + " items" : "null")
                    + ", updateResult="
                    + updateResult
                    + '}';
        }
    }

    /** Represents a single memory item in the response. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MemoryItem {
        @JsonProperty("workspace_id")
        private String workspaceId;

        @JsonProperty("memory_id")
        private String memoryId;

        @JsonProperty("memory_type")
        private String memoryType;

        @JsonProperty("when_to_use")
        private String whenToUse;

        private String content;

        private Double score;

        @JsonProperty("time_created")
        private String timeCreated;

        @JsonProperty("time_modified")
        private String timeModified;

        private String author;

        private Map<String, Object> metadata;

        private String target;

        @JsonProperty("reflection_subject")
        private String reflectionSubject;

        // Getters and Setters

        public String getWorkspaceId() {
            return workspaceId;
        }

        public void setWorkspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
        }

        public String getMemoryId() {
            return memoryId;
        }

        public void setMemoryId(String memoryId) {
            this.memoryId = memoryId;
        }

        public String getMemoryType() {
            return memoryType;
        }

        public void setMemoryType(String memoryType) {
            this.memoryType = memoryType;
        }

        public String getWhenToUse() {
            return whenToUse;
        }

        public void setWhenToUse(String whenToUse) {
            this.whenToUse = whenToUse;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Double getScore() {
            return score;
        }

        public void setScore(Double score) {
            this.score = score;
        }

        public String getTimeCreated() {
            return timeCreated;
        }

        public void setTimeCreated(String timeCreated) {
            this.timeCreated = timeCreated;
        }

        public String getTimeModified() {
            return timeModified;
        }

        public void setTimeModified(String timeModified) {
            this.timeModified = timeModified;
        }

        public String getAuthor() {
            return author;
        }

        public void setAuthor(String author) {
            this.author = author;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getReflectionSubject() {
            return reflectionSubject;
        }

        public void setReflectionSubject(String reflectionSubject) {
            this.reflectionSubject = reflectionSubject;
        }
    }

    /** Update result information. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UpdateResult {
        @JsonProperty("deleted_count")
        private Integer deletedCount;

        @JsonProperty("inserted_count")
        private Integer insertedCount;

        public Integer getDeletedCount() {
            return deletedCount;
        }

        public void setDeletedCount(Integer deletedCount) {
            this.deletedCount = deletedCount;
        }

        public Integer getInsertedCount() {
            return insertedCount;
        }

        public void setInsertedCount(Integer insertedCount) {
            this.insertedCount = insertedCount;
        }

        @Override
        public String toString() {
            return "UpdateResult{"
                    + "deletedCount="
                    + deletedCount
                    + ", insertedCount="
                    + insertedCount
                    + '}';
        }
    }
}
