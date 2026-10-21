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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Message;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Retries stable continuation identities; an ambiguous send never discards the durable intent. */
public final class ManagedResumePublisher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedResumePublisher.class);
    private ScheduledExecutorService dispatcher;
    private final ManagedSuspensions store;
    private final ManagedMessageBus bus;
    private final ManagedLeaseStore leases;
    private final ObjectMapper mapper;

    public ManagedResumePublisher(
            ManagedSuspensions store,
            ManagedMessageBus bus,
            ManagedLeaseStore leases,
            ObjectMapper mapper) {
        this.store = store;
        this.bus = bus;
        this.leases = leases;
        this.mapper = mapper;
    }

    public int retry(int limit) {
        int sent = 0;
        for (var resume : store.pending(limit)) {
            var lease =
                    leases.acquire(
                            resume.scope().key("resume-publication"), Duration.ofSeconds(60));
            if (lease.isEmpty()) continue;
            try {
                byte[] body = mapper.writeValueAsBytes(resume);
                if (!leases.owns(lease.get()))
                    throw new ManagedTransportException("Resume publication lease lost");
                var receipt =
                        bus.publish(
                                Channel.TURN_RESUMES,
                                new Message(
                                        resume.resumeId(), resume.scope().messageGroup(), body));
                store.published(resume, receipt.brokerMessageId());
                ManagedRuntimeLog.info(
                        log,
                        "brain.resume.published",
                        resume.scope(),
                        resume.turnId(),
                        "resume_id",
                        resume.resumeId(),
                        "suspension_id",
                        resume.suspensionId(),
                        "suspension_epoch",
                        resume.suspensionEpoch(),
                        "broker_message_id",
                        receipt.brokerMessageId());
                sent++;
            } catch (ManagedTransportException unavailable) {
                // A subsequent outbox scan republishes the same identity after restart/failure.
            } catch (JsonProcessingException invalid) {
                throw new IllegalArgumentException("Cannot encode resume command", invalid);
            } finally {
                leases.release(lease.get());
            }
        }
        return sent;
    }

    public synchronized void start() {
        if (dispatcher != null) return;
        dispatcher = Executors.newSingleThreadScheduledExecutor();
        dispatcher.scheduleWithFixedDelay(
                () -> {
                    try {
                        retry(64);
                    } catch (RuntimeException unavailable) {
                        log.warn("Resume outbox remains pending: {}", unavailable.toString());
                    }
                },
                0,
                1,
                TimeUnit.SECONDS);
    }

    @Override
    public synchronized void close() {
        if (dispatcher != null) dispatcher.shutdownNow();
    }
}
