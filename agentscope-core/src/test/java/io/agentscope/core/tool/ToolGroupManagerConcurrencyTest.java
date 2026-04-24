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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ToolGroupManagerConcurrencyTest {

    private static final int GROUP_COUNT = 10;
    private static final int WORKER_COUNT = 6;
    private static final int ITERATIONS = 250;

    @Test
    void concurrentReadersAndWritersShouldKeepActiveGroupSnapshotsConsistent() throws Exception {
        ToolGroupManager manager = createManagerWithGroups();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(WORKER_COUNT);
        CyclicBarrier startBarrier = new CyclicBarrier(WORKER_COUNT);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < WORKER_COUNT / 2; worker++) {
                final int workerIndex = worker;
                futures.add(
                        executor.submit(
                                () -> {
                                    await(startBarrier, failures);
                                    for (int iteration = 0;
                                            iteration < ITERATIONS && failures.isEmpty();
                                            iteration++) {
                                        String groupName =
                                                "group-"
                                                        + ((workerIndex + iteration) % GROUP_COUNT);
                                        boolean active = (iteration + workerIndex) % 2 == 0;
                                        try {
                                            manager.updateToolGroups(List.of(groupName), active);
                                        } catch (Throwable error) {
                                            failures.add(error);
                                            return;
                                        }
                                    }
                                }));
            }

            for (int worker = WORKER_COUNT / 2; worker < WORKER_COUNT; worker++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    await(startBarrier, failures);
                                    for (int iteration = 0;
                                            iteration < ITERATIONS && failures.isEmpty();
                                            iteration++) {
                                        try {
                                            List<String> snapshot = manager.getActiveGroups();
                                            assertEquals(
                                                    new HashSet<>(snapshot).size(),
                                                    snapshot.size(),
                                                    "Concurrent snapshots should not contain"
                                                            + " duplicates");
                                            manager.getActivatedNotes();
                                            manager.getNotes();
                                            manager.getActiveToolNames();
                                        } catch (Throwable error) {
                                            failures.add(error);
                                            return;
                                        }
                                    }
                                }));
            }

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertTrue(failures.isEmpty(), formatFailures(failures));
        assertManagerConsistency(manager);
    }

    @Test
    void setActiveGroupsShouldReplacePreviousActiveStateSnapshot() {
        ToolGroupManager manager = createManagerWithGroups();

        manager.setActiveGroups(List.of("group-1", "group-3", "missing-group"));

        assertEquals(
                List.of("group-1", "group-3", "missing-group"),
                manager.getActiveGroups(),
                "setActiveGroups should publish a stable snapshot in the provided order");
        assertTrue(manager.getToolGroup("group-1").isActive());
        assertTrue(manager.getToolGroup("group-3").isActive());
        assertFalse(manager.getToolGroup("group-0").isActive());
        assertFalse(manager.getToolGroup("group-2").isActive());
        assertFalse(manager.getToolGroup("group-4").isActive());

        String notes = manager.getActivatedNotes();
        assertTrue(notes.contains("group-1"));
        assertTrue(notes.contains("group-3"));
        assertFalse(notes.contains("missing-group"));
    }

    @Test
    void copyToShouldPreserveAnIndependentActiveGroupSnapshot() {
        ToolGroupManager source = createManagerWithGroups();
        source.setActiveGroups(List.of("group-2", "group-4", "missing-group"));

        ToolGroupManager copy = new ToolGroupManager();
        source.copyTo(copy);

        assertEquals(source.getToolGroupNames(), copy.getToolGroupNames());
        assertEquals(source.getActiveGroups(), copy.getActiveGroups());
        assertManagerConsistency(copy);

        copy.updateToolGroups(List.of("group-2"), false);
        copy.updateToolGroups(List.of("group-5"), true);

        assertTrue(source.getActiveGroups().contains("group-2"));
        assertFalse(source.getActiveGroups().contains("group-5"));
        assertFalse(copy.getActiveGroups().contains("group-2"));
        assertTrue(copy.getActiveGroups().contains("group-5"));
        assertManagerConsistency(source);
        assertManagerConsistency(copy);
    }

    private ToolGroupManager createManagerWithGroups() {
        ToolGroupManager manager = new ToolGroupManager();
        for (int index = 0; index < GROUP_COUNT; index++) {
            String groupName = "group-" + index;
            manager.createToolGroup(groupName, "Description for " + groupName, index % 2 == 0);
            manager.addToolToGroup(groupName, "tool-" + index);
        }
        return manager;
    }

    private void assertManagerConsistency(ToolGroupManager manager) {
        List<String> activeGroups = manager.getActiveGroups();
        Set<String> activeGroupSet = new HashSet<>(activeGroups);
        assertEquals(
                activeGroupSet.size(),
                activeGroups.size(),
                "activeGroups should stay deduplicated");

        for (String groupName : manager.getToolGroupNames()) {
            ToolGroup group = manager.getToolGroup(groupName);
            assertEquals(
                    activeGroupSet.contains(groupName),
                    group.isActive(),
                    "ToolGroup active flag should stay aligned with activeGroups snapshots");
        }
    }

    private void await(CyclicBarrier startBarrier, Queue<Throwable> failures) {
        try {
            startBarrier.await(10, TimeUnit.SECONDS);
        } catch (Throwable error) {
            failures.add(error);
        }
    }

    private String formatFailures(Queue<Throwable> failures) {
        StringBuilder builder = new StringBuilder();
        for (Throwable failure : failures) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(failure.getClass().getSimpleName())
                    .append(':')
                    .append(' ')
                    .append(failure.getMessage());
        }
        return builder.toString();
    }
}
