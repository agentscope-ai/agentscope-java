/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.a2a.agent.message.MessageConstants;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.agentexecution.SimpleRequestContextBuilder;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

class SanitizingTaskStoreTest {

    @Test
    void stripsHitlCredentialsRecursivelyBeforeSaving() {
        InMemoryTaskStore delegate = new InMemoryTaskStore();
        SanitizingTaskStore store = new SanitizingTaskStore(delegate);
        Map<String, Object> nested =
                Map.of(
                        "safe",
                        "kept",
                        "nested",
                        Map.of(
                                MessageConstants.RESUME_TOKEN_METADATA_KEY,
                                "current-secret",
                                MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY,
                                "next-secret"));
        Message statusMessage = message("status", nested);
        Task original =
                Task.builder()
                        .id("task-1")
                        .contextId("context-1")
                        .status(
                                new TaskStatus(
                                        TaskState.TASK_STATE_INPUT_REQUIRED, statusMessage, null))
                        .history(List.of(message("history", nested)))
                        .artifacts(
                                List.of(
                                        Artifact.builder()
                                                .artifactId("artifact-1")
                                                .parts(
                                                        new DataPart(
                                                                Map.of(
                                                                        "payload",
                                                                        nested,
                                                                        MessageConstants
                                                                                .LOCAL_HANDOFF_METADATA_KEY,
                                                                        "local-secret"),
                                                                nested))
                                                .metadata(nested)
                                                .build()))
                        .metadata(nested)
                        .build();

        store.save(original, false);

        Task saved = delegate.get("task-1");
        assertFalse(saved.toString().contains("current-secret"));
        assertFalse(saved.toString().contains("next-secret"));
        assertFalse(saved.toString().contains("local-secret"));
        assertEquals("kept", saved.metadata().get("safe"));
        assertTrue(
                original.toString().contains("current-secret"),
                "the caller-owned Task stays immutable");
    }

    @Test
    void delegatesGetListAndDeleteWithoutChangingSemantics() {
        InMemoryTaskStore delegate = new InMemoryTaskStore();
        SanitizingTaskStore store = new SanitizingTaskStore(delegate);
        Task task =
                Task.builder()
                        .id("task-2")
                        .contextId("context-2")
                        .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                        .build();

        store.save(task, false);

        assertEquals(task, store.get("task-2"));
        assertEquals(1, store.list(new org.a2aproject.sdk.spec.ListTasksParams()).tasks().size());
        store.delete("task-2");
        assertNull(store.get("task-2"));
    }

    @Test
    void stripsHitlCredentialsFromRootArrayData() {
        InMemoryTaskStore delegate = new InMemoryTaskStore();
        SanitizingTaskStore store = new SanitizingTaskStore(delegate);
        Task task =
                Task.builder()
                        .id("task-array")
                        .contextId("context-array")
                        .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
                        .artifacts(
                                List.of(
                                        Artifact.builder()
                                                .artifactId("artifact-array")
                                                .parts(
                                                        new DataPart(
                                                                new Object[] {
                                                                    Map.of(
                                                                            "safe",
                                                                            "kept",
                                                                            MessageConstants
                                                                                    .RESUME_TOKEN_METADATA_KEY,
                                                                            "array-secret")
                                                                }))
                                                .build()))
                        .build();

        store.save(task, false);

        DataPart savedPart =
                (DataPart) delegate.get("task-array").artifacts().get(0).parts().get(0);
        List<?> savedData = assertInstanceOf(List.class, savedPart.data());
        assertEquals(List.of(Map.of("safe", "kept")), savedData);
        assertFalse(savedData.toString().contains("array-secret"));
    }

    @Test
    void sdkKeepsRequestMetadataOutsideTheMessageAndTaskHistory() {
        String currentToken = "current-request-only-secret";
        String nextToken = "next-request-only-secret";
        Message wireMessage =
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .taskId("task-request")
                        .contextId("context-request")
                        .parts(new TextPart("resume"))
                        .metadata(Map.of("safe", "message"))
                        .build();
        MessageSendParams params =
                MessageSendParams.builder()
                        .message(wireMessage)
                        .metadata(
                                Map.of(
                                        MessageConstants.RESUME_TOKEN_METADATA_KEY,
                                        currentToken,
                                        MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY,
                                        nextToken))
                        .build();

        RequestContext request =
                new SimpleRequestContextBuilder(new InMemoryTaskStore(), false)
                        .setParams(params)
                        .build();

        assertEquals(
                currentToken,
                request.getMetadata().get(MessageConstants.RESUME_TOKEN_METADATA_KEY));
        assertEquals(
                nextToken,
                request.getMetadata().get(MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY));
        assertFalse(request.getMessage().toString().contains(currentToken));
        Task sdkShapedTask =
                Task.builder()
                        .id(request.getTaskId())
                        .contextId(request.getContextId())
                        .status(new TaskStatus(TaskState.TASK_STATE_SUBMITTED))
                        .history(List.of(request.getMessage()))
                        .build();
        assertFalse(sdkShapedTask.toString().contains(currentToken));
        assertFalse(sdkShapedTask.toString().contains(nextToken));
    }

    private static Message message(String id, Map<String, Object> metadata) {
        return Message.builder()
                .role(Message.Role.ROLE_AGENT)
                .messageId(id)
                .parts(new TextPart("safe", metadata))
                .metadata(metadata)
                .build();
    }
}
