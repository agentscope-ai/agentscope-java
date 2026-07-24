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

package io.agentscope.core.a2a.server.hitl;

import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;

/** Final persistence boundary that strips request-only HITL credentials from SDK tasks. */
public final class SanitizingTaskStore implements TaskStore {

    private final TaskStore delegate;

    public SanitizingTaskStore(TaskStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public TaskStore delegate() {
        return delegate;
    }

    @Override
    public void save(Task task, boolean initial) {
        delegate.save(sanitizeTask(task), initial);
    }

    @Override
    public Task get(String taskId) {
        return delegate.get(taskId);
    }

    @Override
    public void delete(String taskId) {
        delegate.delete(taskId);
    }

    @Override
    public ListTasksResult list(ListTasksParams params) {
        return delegate.list(params);
    }

    static Task sanitizeTask(Task task) {
        Objects.requireNonNull(task, "task");
        TaskStatus status = task.status();
        TaskStatus safeStatus =
                status.message() == null
                        ? status
                        : new TaskStatus(
                                status.state(),
                                sanitizeMessage(status.message()),
                                status.timestamp());
        List<Message> history =
                task.history().stream().map(SanitizingTaskStore::sanitizeMessage).toList();
        List<Artifact> artifacts =
                task.artifacts().stream().map(SanitizingTaskStore::sanitizeArtifact).toList();
        return Task.builder(task)
                .status(safeStatus)
                .history(history)
                .artifacts(artifacts)
                .metadata(sanitizeMetadata(task.metadata()))
                .build();
    }

    private static Message sanitizeMessage(Message message) {
        return Message.builder(message)
                .parts(message.parts().stream().map(SanitizingTaskStore::sanitizePart).toList())
                .metadata(sanitizeMetadata(message.metadata()))
                .build();
    }

    private static Artifact sanitizeArtifact(Artifact artifact) {
        return Artifact.builder(artifact)
                .parts(artifact.parts().stream().map(SanitizingTaskStore::sanitizePart).toList())
                .metadata(sanitizeMetadata(artifact.metadata()))
                .build();
    }

    private static Part<?> sanitizePart(Part<?> part) {
        if (part instanceof TextPart text) {
            return new TextPart(text.text(), sanitizeMetadata(text.metadata()));
        }
        if (part instanceof DataPart data) {
            return new DataPart(sanitizeValue(data.data()), sanitizeMetadata(data.metadata()));
        }
        if (part instanceof FilePart file) {
            return new FilePart(file.file(), sanitizeMetadata(file.metadata()));
        }
        return part;
    }

    private static Map<String, Object> sanitizeMetadata(Map<?, ?> metadata) {
        return metadata == null ? null : MessageConvertUtil.stripSensitiveMetadata(metadata);
    }

    private static Object sanitizeValue(Object value) {
        return value == null
                ? null
                : MessageConvertUtil.stripSensitiveMetadata(Map.of("value", value)).get("value");
    }
}
