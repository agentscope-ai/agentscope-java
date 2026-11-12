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
package io.agentcore.managed.server.runtime.managed;

import io.agentcore.managed.harness.runtime.SessionExecutionAccess;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A bounded optional channel; loss never changes native durability or final public facts. */
public final class ManagedHttpPreviews
        implements ManagedJournalStore.PreviewFactory, AutoCloseable {
    private final SessionExecutionAccess.Transport client;
    private final ManagedHttpExecutions executions;
    private final ThreadPoolExecutor sender =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256));

    public ManagedHttpPreviews(SessionServiceClient client, ManagedHttpExecutions executions) {
        this(client::post, executions);
    }

    public ManagedHttpPreviews(
            SessionExecutionAccess.Transport client, ManagedHttpExecutions executions) {
        this.client = client;
        this.executions = executions;
    }

    @Override
    public Consumer<ManagedSessionPreviews.TextDelta> begin(
            ManagedScope scope, String turn, String call, long epoch, String purpose) {
        if (!"REASONING".equals(purpose)) return null;
        var request = executions.request(scope);
        if (!turn.equals(request.required("turnId").asText())
                || epoch != request.required("fence").required("epoch").asLong()) return null;
        request.put("modelCallId", call);
        var stopped = new AtomicBoolean();
        return new Consumer<>() {
            private long sequence;
            private String message;
            private Boolean interested;

            public synchronized void accept(ManagedSessionPreviews.TextDelta chunk) {
                if (stopped.get() || chunk.text() == null || chunk.text().isEmpty()) return;
                if (chunk.messageId() == null
                        || (message != null && !message.equals(chunk.messageId()))) {
                    stopped.set(true);
                    return;
                }
                var texts = new ArrayList<String>();
                for (String text : chunk.text()) {
                    if (text == null) {
                        stopped.set(true);
                        return;
                    }
                    for (int from = 0; from < text.length(); ) {
                        int to = Math.min(from + 1024, text.length());
                        if (to < text.length() && Character.isHighSurrogate(text.charAt(to - 1)))
                            to--;
                        if (texts.size() == 16) {
                            stopped.set(true);
                            return;
                        }
                        texts.add(text.substring(from, to));
                        from = to;
                    }
                }
                if (texts.isEmpty()) return;
                message = chunk.messageId();
                var body =
                        request.deepCopy()
                                .put("messageId", message)
                                .put("sequence", Long.toString(sequence));
                var chunks = body.putArray("chunks");
                texts.forEach(chunks::add);
                sequence += texts.size() + (sequence == 0 ? 1 : 0);
                try {
                    sender.execute(
                            () -> {
                                if (stopped.get()) return;
                                try {
                                    if (interested == null)
                                        interested =
                                                client.post("previews/interest", request)
                                                        .path("interested")
                                                        .asBoolean();
                                    if (!interested
                                            || !client.post("previews/publish", body)
                                                    .path("accepted")
                                                    .asBoolean()) stopped.set(true);
                                } catch (RuntimeException unavailable) {
                                    stopped.set(true);
                                }
                            });
                } catch (RuntimeException full) {
                    stopped.set(true);
                }
            }
        };
    }

    @Override
    public void close() {
        sender.shutdownNow();
    }
}
