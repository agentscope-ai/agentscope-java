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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test {@link Sandbox} that behaves like a Docker container across release: {@link #stop()} ends
 * it and {@link #shutdown()} removes it, after which a workspace write fails the way {@code docker
 * exec} does. Lifecycle steps and uploads are appended to a shared event log so tests can assert
 * their order.
 */
public final class TrackingSandbox implements Sandbox {

    private final List<String> events;
    private final SandboxState state = new SandboxState() {};
    private volatile boolean running;
    private volatile boolean removed;
    private volatile CountDownLatch uploadGate;
    private final CountDownLatch uploadStarted = new CountDownLatch(1);

    public TrackingSandbox(List<String> events) {
        this.events = events;
    }

    public static List<String> newEventLog() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    /** Makes uploads block until {@code gate} is released, to keep a background write in flight. */
    public void blockUploadsUntil(CountDownLatch gate) {
        this.uploadGate = gate;
    }

    /** Waits until an upload has reached this sandbox. */
    public boolean awaitUploadStarted(long timeout, TimeUnit unit) throws InterruptedException {
        return uploadStarted.await(timeout, unit);
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        events.add("stop");
        running = false;
    }

    @Override
    public void shutdown() {
        events.add("shutdown");
        removed = true;
    }

    @Override
    public void close() {
        stop();
        shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public SandboxState getState() {
        return state;
    }

    @Override
    public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        throw new UnsupportedOperationException("exec");
    }

    @Override
    public InputStream persistWorkspace() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public void hydrateWorkspace(InputStream archive) throws Exception {
        uploadStarted.countDown();
        CountDownLatch gate = uploadGate;
        if (gate != null && !gate.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("upload gate was never released");
        }
        if (!running || removed) {
            events.add("upload-failed");
            throw new IllegalStateException("container is not running");
        }
        archive.readAllBytes();
        events.add("upload");
    }
}
