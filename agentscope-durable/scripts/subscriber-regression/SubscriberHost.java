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
package io.agentscope.durable.examples.brainhands.echo;

import io.agentscope.durable.*;
import io.agentscope.durable.DurableTypes.*;
import io.agentscope.extensions.durable.jdbc.PostgresBackend;
import org.postgresql.ds.PGSimpleDataSource;

public final class SubscriberHost {
    static final SessionKey KEY = new SessionKey("regression", "session");

    static PGSimpleDataSource database() {
        var source = new PGSimpleDataSource();
        source.setURL(
                "jdbc:postgresql://pg/postgres?user=postgres&connectTimeout=4&socketTimeout=10");
        return source;
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("init")) {
            PostgresBackend.initializeSchema(database());
            return;
        }
        try (var backend = PostgresBackend.builder().dataSource(database()).build()) {
            Executor echo = new Executor() {
                public Capabilities capabilities() { return new Capabilities(true, true, false); }
                public ExecutionResult startExecution(ExecutionRequest request) { return query(request, null); }
                public ExecutionResult query(ExecutionRequest request, String handle) {
                    return new ExecutionResult(ExecutionState.SUCCEEDED, request.executionId(), DurableJson.value("echo"), null);
                }
                public ExecutionResult cancel(ExecutionRequest request, String handle) { return query(request, handle); }
            };
            try (var runtime = EchoBrainExample.runtime(backend, echo)) {
                var session = runtime.createSession(KEY, EchoBrainExample.HARNESS);
                if (args[0].equals("brain")) {
                    runtime.start();
                    System.out.println("READY");
                    Thread.sleep(Long.MAX_VALUE);
                } else if (args[0].equals("prune")) backend.pruneEvents(KEY, session.snapshot().cursor());
                else {
                    var turn = session.submit(args[0], "Call echo");
                    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
                    while (!turn.status().status().terminal() && System.nanoTime() < end) Thread.sleep(50);
                    if (turn.result().status() != TurnStatus.SUCCEEDED) throw new AssertionError(turn.result());
                    System.out.println("TURN " + turn.turnId());
                }
            }
        }
    }
}
