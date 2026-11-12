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
package io.agentcore.managed.server.toolworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedAdministrativeTools;
import io.agentcore.managed.server.runtime.managed.ManagedAsyncToolExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedBatchCodec;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedLeaseStore;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedPublicResources;
import io.agentcore.managed.server.runtime.managed.ManagedQueryViews;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommandPublisher;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultPublisher;
import io.agentcore.managed.server.runtime.managed.ManagedToolTransport;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerCommands;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerLoop;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerStore;
import io.agentcore.managed.server.runtime.managed.ManagedTransportException;
import io.agentcore.managed.server.runtime.managed.projection.PostgresSessionProjectionStore;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The production command, sealed target and result handlers over a deterministic MQ fixture. */
public final class ManagedToolWorkerTestPipeline implements AutoCloseable {
    public final ManagedToolWorkerStore toolWorker;
    public final ManagedAdministrativeTools administrative;
    public final ManagedToolWorkerCatalog targets;
    public final ManagedToolResultPublisher results;
    public final VaultCrypto crypto;
    public final ManagedMessageBus bus;
    public final ManagedBatchCodec codec;
    private final ManagedToolTransport transport;
    private final ManagedToolWorkerLoop loop;
    private final ManagedToolCommandPublisher publications;
    private final ManagedJournalStore harnessRuntime;
    private final ScheduledExecutorService renewals = Executors.newScheduledThreadPool(2);
    private final ConcurrentLinkedQueue<Exception> failures = new ConcurrentLinkedQueue<>();

    public static ManagedQueryViews configureQuery(
            ManagedJournalStore harnessRuntime, DataSource source, ObjectMapper mapper) {
        var query =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:worker-query-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        PostgresSessionProjectionStore.initialize(query);
        var views =
                new ManagedQueryViews(
                        new PostgresSessionProjectionStore(query, mapper),
                        mapper,
                        "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8),
                        "v1",
                        1000,
                        new ManagedPublicResources(source, mapper));
        harnessRuntime.queryViews(views);
        return views;
    }

    public ManagedToolWorkerTestPipeline(
            DataSource source,
            ObjectMapper mapper,
            ManagedJournalStore harnessRuntime,
            ManagedCatalog catalog,
            ManagedLeaseStore leases,
            Function<ManagedScope, ManagedToolExecutor> providers,
            Path blobs) {
        this.harnessRuntime = harnessRuntime;
        configureQuery(harnessRuntime, source, mapper);

        toolWorker = new ManagedToolWorkerStore(source, mapper);
        crypto = new VaultCrypto(new StandardEnvironment(), "01234567890123456789012345678901");
        targets = new ManagedToolWorkerCatalog(toolWorker, mapper, crypto);
        bus = new InMemoryManagedMessageBus(System::currentTimeMillis, 8);
        codec = new ManagedBatchCodec(mapper, blobs);
        transport = harnessRuntime.transport();
        var descriptions = new ManagedMcpCatalog(source, mapper, crypto, transport);
        administrative =
                new ManagedAdministrativeTools(source, mapper, transport, descriptions, catalog);
        results = new ManagedToolResultPublisher(toolWorker, bus, codec);
        var executions =
                new ManagedAsyncToolExecutions(
                        leases,
                        toolWorker,
                        results,
                        renewals,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        Duration.ofMillis(10));
        loop =
                new ManagedToolWorkerLoop(
                        toolWorker,
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
                        },
                        16384,
                        64,
                        Duration.ofMillis(100));
        loop.start();
        var commands =
                new ManagedToolWorkerCommands(toolWorker, codec, results, loop, transport::confirm);
        bus.subscribe(
                ManagedMessageBus.Channel.TOOL_EXECUTE,
                2,
                commands,
                (delivery, failure) -> failures.add(failure));
        bus.subscribe(
                ManagedMessageBus.Channel.TOOL_CONTROL,
                2,
                commands,
                (delivery, failure) -> failures.add(failure));
        bus.subscribe(
                ManagedMessageBus.Channel.TOOL_EVENTS,
                2,
                delivery -> {
                    var event = codec.decodeToolEvent(delivery.message());
                    if (!administrative.receive(event)) harnessRuntime.commitToolEvent(event);
                },
                (delivery, failure) -> failures.add(failure));
        publications = new ManagedToolCommandPublisher(source, bus, codec, mapper);
        harnessRuntime.transport().onPublicationReady(publications::wakeup);
        publications.start();
        transport.start(bus, codec);
        results.start();
    }

    public void check() {
        for (var scope : harnessRuntime.projections().rebuildScopes(128))
            while (harnessRuntime.projections().project(scope, 128) > 0) {
                /* Explicit Agent Service fixture. */
            }
        if (!failures.isEmpty())
            throw new AssertionError("Production protocol failed", failures.peek());
    }

    public void close() {
        publications.close();
        loop.close();
        transport.close();
        results.close();
        renewals.shutdownNow();
        bus.close();
    }
}
