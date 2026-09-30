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
package io.agentscope.harness.agent.testing;

import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.session.SessionTree;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Auto-registered JUnit Jupiter extension that drains fire-and-forget harness background
 * tasks (memory flush/maintenance and session/transcript mirrors) after every test method.
 *
 * <p>{@link io.agentscope.harness.agent.HarnessAgent#close()} drains the same trackers, but
 * many harness tests build a transient {@code HarnessAgent}, call {@code .block()}, and let it
 * be garbage-collected without closing it. The fire-and-forget memory flush dispatched in
 * {@code MemoryFlushMiddleware#onAgent}'s {@code doOnComplete} then races with JUnit's
 * {@code @TempDir} teardown: the async write still holds file handles (or creates files after
 * the walk) when the temp directory is deleted, producing the flaky
 * {@code "Failed to delete temp directory"} / {@code "Failed to close extension context"}
 * errors seen across harness integration tests on both Linux and Windows runners.
 *
 * <p>This callback runs after the test method and after {@code @AfterEach} methods, but
 * <em>before</em> the JUnit {@code TempDir} extension closes the extension context and deletes
 * the temp directory, so the background writes have quiesced first. When nothing is in flight
 * both {@code await*} calls return immediately, making this a no-op for tests that never
 * trigger a flush. If the trackers fail to quiesce within the timeout (or the thread is
 * interrupted), the callback throws {@link AssertionError} so the failure is deterministic and
 * points at the root cause rather than surfacing later as an opaque
 * {@code "Failed to delete temp directory"}.
 */
public class HarnessBackgroundTaskQuiescenceExtension implements AfterEachCallback {

    private static final long TIMEOUT_SECONDS = 5;

    @Override
    public void afterEach(ExtensionContext context) {
        boolean mirrorsQuiet = SessionTree.awaitMirrorQuiescence(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        boolean flushQuiet =
                MemoryBackgroundTasks.awaitQuiescence(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!mirrorsQuiet || !flushQuiet) {
            throw new AssertionError(
                    "Harness background tasks did not quiesce within "
                            + TIMEOUT_SECONDS
                            + "s; fire-and-forget memory flush may still race with @TempDir"
                            + " teardown");
        }
    }
}
