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
package io.agentcore.managed.server.agentservice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentcore.managed.server.agentservice.session.ManagedSessionEventStreams;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointObjects;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real execution commits; query visibility wakes replicas without PostgreSQL LISTEN/NOTIFY. */
@EnabledIfEnvironmentVariable(
        named = "MANAGED_AGENTS_AGENT_SERVICE_SSE_TEST_DATABASE_URI",
        matches = ".+")
class ManagedSessionEventStreamsPostgresTest extends ManagedSessionEventStreamsTest {
    @Override
    protected DataSource database() {
        return new DriverManagerDataSource(
                System.getenv("MANAGED_AGENTS_AGENT_SERVICE_SSE_TEST_DATABASE_URI"),
                "managed",
                "managed-local-test");
    }

    @Test
    void anotherReplicaReceivesVisibleCommitsAndReconnectsFromItsOwnCursor() throws Exception {
        var remote = new SessionEventNotifier(source, 100);
        // Redis transports this best-effort signal in production; its failure is tested separately.
        journal.projections().visible(session -> remote.refresh(session.messageGroup()));
        var other = replica(remote, 60000);
        var first = other.stream(scope, 0).next().toFuture();
        initialPage(other);
        append(journal, 1);
        assertEquals(1, first.get(3, TimeUnit.SECONDS).sequence());
        var reconnected = other.stream(scope, 1).next().toFuture();
        initialPage(other);
        append(journal, 2);
        assertEquals(2, reconnected.get(3, TimeUnit.SECONDS).sequence());
    }

    @Test
    void lostCrossReplicaNotificationRecoversWithoutAnotherEvent() throws Exception {
        var other = replica(new SessionEventNotifier(source, 100), 100);
        journal.projections().visible(session -> {});
        var first = other.stream(scope, 0).next().toFuture();
        initialPage(other);
        append(journal, 1);
        assertEquals(1, first.get(3, TimeUnit.SECONDS).sequence());
    }

    private ManagedSessionEventStreams replica(SessionEventNotifier notifier, long repairMs) {
        var store =
                AgentServiceQueryFixture.journal(
                        source, mapper, new ManagedCheckpointObjects(mapper, objects));
        return new ManagedSessionEventStreams(store, notifier, repairMs, 50000, 30);
    }
}
