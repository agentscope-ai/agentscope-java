/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.hands;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.ManagedCatalog;
import io.agentscope.builder.runtime.managed.InMemoryManagedInputInbox;
import io.agentscope.builder.runtime.managed.InMemoryManagedMessageBus;
import io.agentscope.builder.runtime.managed.ManagedAdministrativeTools;
import io.agentscope.builder.runtime.managed.ManagedBatchCodec;
import io.agentscope.builder.runtime.managed.ManagedHandsCommands;
import io.agentscope.builder.runtime.managed.ManagedHandsLoop;
import io.agentscope.builder.runtime.managed.ManagedHandsStore;
import io.agentscope.builder.runtime.managed.ManagedJournalStore;
import io.agentscope.builder.runtime.managed.ManagedLeaseStore;
import io.agentscope.builder.runtime.managed.ManagedMcpCatalog;
import io.agentscope.builder.runtime.managed.ManagedMessageBus;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedToolExecutions;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor;
import io.agentscope.builder.runtime.managed.ManagedToolResultPublisher;
import io.agentscope.builder.runtime.managed.ManagedToolTransport;
import io.agentscope.builder.runtime.managed.ManagedTransportException;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataAccessException;

/** The production command, sealed target and result handlers over a deterministic MQ fixture. */
public final class ManagedHandsTestPipeline implements AutoCloseable {
    public final ManagedHandsStore hands;
    public final ManagedAdministrativeTools administrative;
    public final ManagedHandsCatalog targets;
    public final ManagedToolResultPublisher results;
    public final VaultCrypto crypto;
    public final ManagedMessageBus bus;
    public final ManagedBatchCodec codec;
    private final ManagedToolTransport transport;
    private final ManagedHandsLoop loop;
    private final ScheduledExecutorService renewals = Executors.newScheduledThreadPool(2);
    private final ConcurrentLinkedQueue<Exception> failures = new ConcurrentLinkedQueue<>();

    public ManagedHandsTestPipeline(
            DataSource source,
            ObjectMapper mapper,
            ManagedJournalStore brain,
            ManagedCatalog catalog,
            ManagedLeaseStore leases,
            Function<ManagedScope, ManagedToolExecutor> providers,
            Path blobs) {
        hands = new ManagedHandsStore(source, mapper);
        crypto = new VaultCrypto(new StandardEnvironment(), "01234567890123456789012345678901");
        targets = new ManagedHandsCatalog(hands, mapper, crypto);
        bus = new InMemoryManagedMessageBus(System::currentTimeMillis, 8);
        codec = new ManagedBatchCodec(mapper, blobs);
        transport = brain.transport();
        var descriptions = new ManagedMcpCatalog(source, mapper, crypto, transport);
        administrative =
                new ManagedAdministrativeTools(source, mapper, transport, descriptions, catalog);
        results = new ManagedToolResultPublisher(hands, bus, codec);
        var executions =
                new ManagedToolExecutions(
                        leases,
                        new InMemoryManagedInputInbox(System::currentTimeMillis),
                        hands,
                        results,
                        renewals,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        Duration.ofMillis(10));
        loop =
                new ManagedHandsLoop(
                        hands,
                        executions,
                        4,
                        Duration.ofSeconds(60),
                        Duration.ofMillis(100),
                        (scope, task) -> providers.apply(scope),
                        (ref, failure) -> {
                            // These used to suspend the MQ callback. After admission ACK, the
                            // durable owner scan retries the same task instead.
                            if (!(failure instanceof ManagedTransportException)
                                    && !(failure instanceof DataAccessException))
                                failures.add(failure);
                        });
        loop.start();
        var commands =
                new ManagedHandsCommands(hands, codec, results, loop::wakeup, transport::confirm);
        bus.subscribe(
                ManagedMessageBus.Channel.TOOL_EXECUTE,
                2,
                commands::consume,
                (delivery, failure) -> failures.add(failure));
        bus.subscribe(
                ManagedMessageBus.Channel.TOOL_CONTROL,
                2,
                commands::consume,
                (delivery, failure) -> failures.add(failure));
        bus.subscribe(
                ManagedMessageBus.Channel.TOOL_EVENTS,
                2,
                delivery -> {
                    var event = codec.decodeToolEvent(delivery.message());
                    if (!administrative.receive(event)) brain.commitToolEvent(event);
                },
                (delivery, failure) -> failures.add(failure));
        transport.start(bus, codec);
        results.start();
    }

    public void check() {
        if (!failures.isEmpty())
            throw new AssertionError("Production protocol failed", failures.peek());
    }

    public void close() {
        loop.close();
        transport.close();
        results.close();
        renewals.shutdownNow();
        bus.close();
    }
}
