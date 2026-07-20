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
package io.agentscope.spring.boot.responses.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.responses.converter.ResponsesValidationException;
import io.agentscope.core.responses.model.ResponsesConversation;
import io.agentscope.core.responses.model.ResponsesList;
import io.agentscope.core.responses.model.ResponsesRequest;
import io.agentscope.core.responses.model.ResponsesResponse;
import io.agentscope.spring.boot.responses.service.ResponsesStateService.PreparedRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.Disposables;

class ResponsesStateServiceTest {

    @Test
    void shouldCommitAutoConversationOnlyAfterPreparedInputIsValidated() {
        ResponsesStateService service = new ResponsesStateService();
        ResponsesRequest request = new ResponsesRequest();
        request.setInput("Hello");
        request.setConversation("auto");

        PreparedRequest prepared = service.prepare(request);

        assertThat(prepared.pendingConversation()).isTrue();
        assertThatThrownBy(() -> service.retrieveConversation(prepared.conversationId()))
                .isInstanceOf(ResponsesValidationException.class);

        service.commitConversation(prepared);

        assertThat(service.retrieveConversation(prepared.conversationId()).getId())
                .isEqualTo(prepared.conversationId());
    }

    @Test
    void shouldHandleConcurrentConversationItemAppendsAndReads() throws Exception {
        ResponsesStateService service = new ResponsesStateService();
        ResponsesConversation conversation = service.createConversation(null);
        int itemCount = 64;
        List<Runnable> tasks = new ArrayList<>();

        for (int i = 0; i < itemCount; i++) {
            int index = i;
            tasks.add(
                    () ->
                            service.createConversationItems(
                                    conversation.getId(), List.of(message(index))));
        }
        for (int i = 0; i < itemCount; i++) {
            String order = i % 2 == 0 ? "asc" : "desc";
            tasks.add(() -> service.listConversationItems(conversation.getId(), null, null, order));
        }

        runConcurrently(tasks);

        ResponsesList<Object> items =
                service.listConversationItems(conversation.getId(), null, null, "asc");
        assertThat(items.getData()).hasSize(itemCount);
        assertThat(items.getData())
                .extracting(this::itemContent)
                .containsExactlyInAnyOrderElementsOf(
                        IntStream.range(0, itemCount).mapToObj(i -> "item-" + i).toList());
        assertThat(items.getData()).extracting(this::itemId).doesNotContainNull();
        assertThat(items.getData()).extracting(this::itemId).doesNotHaveDuplicates();
    }

    @Test
    void shouldHandleConcurrentConversationItemDeletesAndReads() throws Exception {
        ResponsesStateService service = new ResponsesStateService();
        ResponsesConversation conversation = service.createConversation(null);
        int itemCount = 64;
        ResponsesList<Object> created =
                service.createConversationItems(
                        conversation.getId(),
                        IntStream.range(0, itemCount).mapToObj(i -> (Object) message(i)).toList());
        List<String> itemIds = created.getData().stream().map(this::itemId).toList();
        List<Runnable> tasks = new ArrayList<>();

        for (String itemId : itemIds) {
            tasks.add(() -> service.deleteConversationItem(conversation.getId(), itemId));
        }
        for (int i = 0; i < itemCount; i++) {
            tasks.add(() -> service.listConversationItems(conversation.getId(), null, null, null));
        }

        runConcurrently(tasks);

        ResponsesList<Object> remaining =
                service.listConversationItems(conversation.getId(), null, null, null);
        assertThat(remaining.getData()).isEmpty();
    }

    @Test
    void shouldIgnoreLateBackgroundCompletionAfterCancellation() {
        ResponsesStateService service = new ResponsesStateService();
        ResponsesConversation conversation = service.createConversation(null);
        PreparedRequest prepared = prepared(conversation.getId());
        Disposable.Swap task = Disposables.swap();
        ResponsesResponse queued = backgroundResponse("resp_cancel", "queued", "");
        service.saveBackground(queued, prepared, task);

        ResponsesResponse cancelled = service.cancelResponse(queued.getId());
        Disposable lateSubscription = Disposables.single();
        task.update(lateSubscription);
        service.completeBackground(
                backgroundResponse(queued.getId(), "completed", "late result"), prepared);

        assertThat(task.isDisposed()).isTrue();
        assertThat(lateSubscription.isDisposed()).isTrue();
        assertThat(cancelled.getStatus()).isEqualTo("cancelled");
        assertThat(service.retrieveResponse(queued.getId())).isSameAs(cancelled);
        assertThat(service.retrieveResponse(queued.getId()).getOutputText()).isEmpty();
        assertThat(service.listConversationItems(conversation.getId(), null, null, null).getData())
                .isEmpty();
    }

    @Test
    void shouldNotRestoreDeletedResponseWhenBackgroundCompletionArrivesLate() {
        ResponsesStateService service = new ResponsesStateService();
        ResponsesConversation conversation = service.createConversation(null);
        PreparedRequest prepared = prepared(conversation.getId());
        Disposable.Swap task = Disposables.swap();
        ResponsesResponse queued = backgroundResponse("resp_delete", "queued", "");
        service.saveBackground(queued, prepared, task);

        service.deleteResponse(queued.getId());
        Disposable lateSubscription = Disposables.single();
        task.update(lateSubscription);
        service.completeBackground(
                backgroundResponse(queued.getId(), "completed", "late result"), prepared);

        assertThat(task.isDisposed()).isTrue();
        assertThat(lateSubscription.isDisposed()).isTrue();
        assertThatThrownBy(() -> service.retrieveResponse(queued.getId()))
                .isInstanceOf(ResponsesValidationException.class);
        assertThat(service.listConversationItems(conversation.getId(), null, null, null).getData())
                .isEmpty();
    }

    private void runConcurrently(List<Runnable> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    task.run();
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Map<String, Object> message(int index) {
        return Map.of("type", "message", "role", "user", "content", "item-" + index);
    }

    private PreparedRequest prepared(String conversationId) {
        return new PreparedRequest(new ResponsesRequest(), List.of(message(0)), conversationId);
    }

    private ResponsesResponse backgroundResponse(String id, String status, String outputText) {
        ResponsesResponse response = new ResponsesResponse();
        response.setId(id);
        response.setStatus(status);
        response.setBackground(true);
        response.setStore(true);
        response.setOutput(List.of());
        response.setOutputText(outputText);
        return response;
    }

    private String itemId(Object item) {
        return (String) ((Map<?, ?>) item).get("id");
    }

    private String itemContent(Object item) {
        return (String) ((Map<?, ?>) item).get("content");
    }
}
