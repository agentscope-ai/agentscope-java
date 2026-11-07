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
package io.agentscope.harness.agent.sandbox.impl.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for issue #3644: the container name is derived from the session id, so a
 * container the daemon still holds under that name can outlive the id persisted in the session
 * state. When that happens the sandbox has to reconcile with the daemon instead of trying to
 * create a second container under a claimed name, which failed with exit 125 on every turn and
 * left the session permanently unable to start.
 */
class DockerSandboxContainerReconcileTest {

    private static final String SESSION_ID = "wedge";
    private static final String CONTAINER_NAME = "agentscope-sandbox-" + SESSION_ID;

    /**
     * Fake daemon: containers are addressed by name or id, and {@code docker run} refuses a
     * claimed name exactly like the real CLI does.
     */
    static class FakeDocker extends DockerSandbox {

        private final Map<String, String> idByName = new HashMap<>();
        private final Set<String> runningIds = new HashSet<>();
        private final List<String> calls = new ArrayList<>();
        private int created;
        private boolean daemonUnreachable;

        FakeDocker(DockerSandboxState state) {
            super(state);
        }

        void createContainer(String name, String id, boolean running) {
            idByName.put(name, id);
            if (running) {
                runningIds.add(id);
            }
        }

        void makeDaemonUnreachable() {
            daemonUnreachable = true;
        }

        @Override
        CliResult runDockerCli(int timeoutSeconds, String... command) {
            calls.add(String.join(" ", command));
            String subcommand = command.length > 1 ? command[1] : "";
            String target = command[command.length - 1];
            switch (subcommand) {
                case "inspect":
                    if (daemonUnreachable) {
                        return new CliResult(
                                1,
                                "",
                                "Cannot connect to the Docker daemon at unix:///var/run.sock");
                    }
                    return inspect(command[3], target);
                case "run":
                    String runName =
                            command[java.util.Arrays.asList(command).indexOf("--name") + 1];
                    if (idByName.containsKey(runName)) {
                        return new CliResult(
                                125,
                                "",
                                "docker: Error response from daemon: Conflict. The container name"
                                        + " \"/"
                                        + runName
                                        + "\" is already in use by container \""
                                        + idByName.get(runName)
                                        + "\".");
                    }
                    created++;
                    String newId = "created-" + created;
                    createContainer(runName, newId, true);
                    return new CliResult(0, newId, "");
                case "start":
                    String startedId = idByName.get(target);
                    if (startedId == null) {
                        return new CliResult(
                                1, "", "Error response from daemon: No such container: " + target);
                    }
                    runningIds.add(startedId);
                    return new CliResult(0, target, "");
                default:
                    return new CliResult(0, "", "");
            }
        }

        private CliResult inspect(String format, String target) {
            String id = idByName.get(target);
            if (id == null) {
                for (String candidate : idByName.values()) {
                    if (candidate.equals(target)) {
                        id = candidate;
                    }
                }
            }
            if (id == null) {
                return new CliResult(1, "", "Error: No such object: " + target);
            }
            if (format.contains("{{.Id}}")) {
                return new CliResult(0, id, "");
            }
            return new CliResult(0, runningIds.contains(id) ? "true" : "false", "");
        }

        boolean createdAnyContainer() {
            return created > 0;
        }
    }

    private static DockerSandboxState stateWithContainerId(String containerId) {
        DockerSandboxState state = new DockerSandboxState();
        state.setSessionId(SESSION_ID);
        state.setImage("ubuntu:22.04");
        state.setWorkspaceRoot("/workspace");
        state.setWorkspaceSpec(new WorkspaceSpec());
        state.setContainerId(containerId);
        return state;
    }

    private static void ensureContainerRunning(DockerSandbox sandbox) throws Exception {
        Method m = DockerSandbox.class.getDeclaredMethod("doEnsureContainerRunning");
        m.setAccessible(true);
        try {
            m.invoke(sandbox);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    @Test
    void adoptsTheRunningContainerTheDaemonStillHoldsByName() throws Exception {
        DockerSandboxState state = stateWithContainerId("stale-id");
        FakeDocker sandbox = new FakeDocker(state);
        sandbox.createContainer(CONTAINER_NAME, "live-id", true);

        ensureContainerRunning(sandbox);

        assertEquals(
                "live-id",
                state.getContainerId(),
                "the persisted id must be repaired to the container the daemon actually runs");
        assertEquals(CONTAINER_NAME, state.getContainerName());
        assertFalse(
                sandbox.createdAnyContainer(),
                "a second container must never be created under a claimed name");
    }

    @Test
    void startsTheStoppedContainerTheDaemonStillHoldsByName() throws Exception {
        DockerSandboxState state = stateWithContainerId("stale-id");
        FakeDocker sandbox = new FakeDocker(state);
        sandbox.createContainer(CONTAINER_NAME, "live-id", false);

        ensureContainerRunning(sandbox);

        assertEquals("live-id", state.getContainerId());
        assertFalse(sandbox.createdAnyContainer());
        assertTrue(
                sandbox.calls.stream().anyMatch(c -> c.startsWith("docker start")),
                "a stopped container must be started rather than replaced");
    }

    @Test
    void createsAContainerWhenTheNameIsFree() throws Exception {
        DockerSandboxState state = stateWithContainerId("stale-id");
        FakeDocker sandbox = new FakeDocker(state);

        ensureContainerRunning(sandbox);

        assertTrue(sandbox.createdAnyContainer());
        assertNotNull(state.getContainerId());
        assertTrue(state.getContainerId().startsWith("created-"));
    }

    @Test
    void anUnansweredProbeDoesNotReplaceAWorkingContainer() throws Exception {
        DockerSandboxState state = stateWithContainerId("live-id");
        FakeDocker sandbox = new FakeDocker(state);
        sandbox.createContainer(CONTAINER_NAME, "live-id", true);
        sandbox.makeDaemonUnreachable();

        ensureContainerRunning(sandbox);

        assertEquals("live-id", state.getContainerId());
        assertFalse(
                sandbox.createdAnyContainer(),
                "a probe that cannot answer is not evidence that the container is gone");
    }
}
