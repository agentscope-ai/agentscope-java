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
package io.agentscope.core.tool.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;

class ShellCommandApprovalTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void requestsApprovalOnSubscriptionBeforeRunningCommand(boolean approved, @TempDir Path dir) {
        Thread caller = Thread.currentThread();
        AtomicInteger approvals = new AtomicInteger();
        AtomicReference<Thread> approvalThread = new AtomicReference<>();
        ShellCommandTool tool =
                new ShellCommandTool(
                        dir.toString(),
                        Set.of("pwd"),
                        command -> {
                            approvalThread.set(Thread.currentThread());
                            approvals.incrementAndGet();
                            return approved;
                        });

        var result =
                tool.callAsync(
                        ToolCallParam.builder()
                                .input(Map.of("command", "echo approved > marker.txt"))
                                .build());
        assertEquals(0, approvals.get());

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = ((TextBlock) block.getOutput().get(0)).getText();
                            assertTrue(
                                    text.contains(approved ? "<returncode>0" : "rejected by user"),
                                    text);
                        })
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertEquals(1, approvals.get());
        assertNotSame(caller, approvalThread.get());
        assertEquals(approved, Files.exists(dir.resolve("marker.txt")));
    }
}
