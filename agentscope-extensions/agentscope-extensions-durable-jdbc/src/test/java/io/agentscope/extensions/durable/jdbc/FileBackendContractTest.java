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

package io.agentscope.extensions.durable.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.contract.SessionBackendContract;
import io.agentscope.durable.store.RecordStore;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class FileBackendContractTest extends SessionBackendContract {
    @Override
    protected RecordStore openStore() {
        return new FileRecordStore(directory.resolve("records"));
    }

    @Test
    void competingProcessesAndCrashPreserveCommittedState() throws Exception {
        var first = start("increment");
        var second = start("increment");
        assertTrue(first.waitFor(30, TimeUnit.SECONDS));
        assertTrue(second.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, first.exitValue());
        assertEquals(0, second.exitValue());
        var crash = start("crash");
        assertTrue(crash.waitFor(30, TimeUnit.SECONDS));
        assertEquals(73, crash.exitValue());
        try (var store = openStore()) {
            int count = store.transaction("counter", tx -> tx.get("").asInt());
            assertEquals(40, count);
            assertTrue(store.ready("unfinished", 1).isEmpty());
        }
    }

    private Process start(String mode) throws Exception {
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty(
                                "surefire.test.class.path", System.getProperty("java.class.path")),
                        getClass().getName(),
                        directory.resolve("records").toString(),
                        mode)
                .inheritIO()
                .start();
    }

    public static void main(String[] args) {
        try (var store = new FileRecordStore(Path.of(args[0]))) {
            for (int i = 0; i < 20; i++)
                store.transaction(
                        "counter",
                        tx -> {
                            var previous = tx.get("");
                            tx.put(
                                    "",
                                    DurableJson.value(previous == null ? 1 : previous.asInt() + 1));
                            if (args[1].equals("crash")) {
                                tx.schedule("unfinished", "one", 0, DurableJson.value("pending"));
                                Runtime.getRuntime().halt(73);
                            }
                            return null;
                        });
        }
    }
}
