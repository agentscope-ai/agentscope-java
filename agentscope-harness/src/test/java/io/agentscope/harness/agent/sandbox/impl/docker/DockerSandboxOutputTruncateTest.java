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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests for the configurable exec output truncation cap (issue #3721): the default stays 512
 * KB per stream, a configured value flows from spec/options into the persisted state, and the
 * sandbox resolves it with a fallback for legacy or hand-edited state.
 */
class DockerSandboxOutputTruncateTest {

    private static final int DEFAULT_CAP = 512 * 1024;

    private static DockerSandboxState minimalState() {
        DockerSandboxState state = new DockerSandboxState();
        state.setImage("ubuntu:22.04");
        state.setWorkspaceRoot("/workspace");
        state.setWorkspaceSpec(new WorkspaceSpec());
        return state;
    }

    private static int resolveCap(DockerSandboxState state) throws Exception {
        DockerSandbox sandbox = new DockerSandbox(state);
        Method m = DockerSandbox.class.getDeclaredMethod("outputTruncateBytes");
        m.setAccessible(true);
        return (int) m.invoke(sandbox);
    }

    private static String readStream(InputStream in, int maxBytes) throws Exception {
        Method m =
                DockerSandbox.class.getDeclaredMethod("readStream", InputStream.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, in, maxBytes);
    }

    @Test
    void optionsDefaultToNullAndAcceptCustomCap() {
        DockerSandboxClientOptions options = new DockerSandboxClientOptions();
        assertNull(options.getOutputTruncateBytes());

        options.outputTruncateBytes(2 * 1024 * 1024);
        assertEquals(2 * 1024 * 1024, options.getOutputTruncateBytes());

        options.outputTruncateBytes(null);
        assertNull(options.getOutputTruncateBytes());
    }

    @Test
    void optionsRejectNonPositiveCap() {
        DockerSandboxClientOptions options = new DockerSandboxClientOptions();
        assertThrows(IllegalArgumentException.class, () -> options.outputTruncateBytes(0));
        assertThrows(IllegalArgumentException.class, () -> options.outputTruncateBytes(-1));
        assertThrows(IllegalArgumentException.class, () -> options.setOutputTruncateBytes(0));
    }

    @Test
    void specSetterReachesOptions() {
        DockerFilesystemSpec spec = new DockerFilesystemSpec().outputTruncateBytes(1024 * 1024);
        assertEquals(
                1024 * 1024,
                ((DockerSandboxClientOptions) spec.clientOptions()).getOutputTruncateBytes());
    }

    @Test
    void clientCreateCopiesCapIntoState() {
        Sandbox sandbox =
                new DockerSandboxClient()
                        .create(
                                new WorkspaceSpec(),
                                null,
                                new DockerSandboxClientOptions().outputTruncateBytes(2048));

        assertEquals(2048, ((DockerSandboxState) sandbox.getState()).getOutputTruncateBytes());
    }

    @Test
    void stateRoundTripPreservesCapAndLegacyStateFallsBack() throws Exception {
        DockerSandboxClient client = new DockerSandboxClient();
        DockerSandboxState state = minimalState();
        state.setOutputTruncateBytes(4096);

        DockerSandboxState restored =
                (DockerSandboxState) client.deserializeState(client.serializeState(state));
        assertEquals(4096, restored.getOutputTruncateBytes());

        // State persisted before the field existed must deserialize without it.
        String legacyJson =
                client.serializeState(state).replaceAll(",?\"outputTruncateBytes\":4096", "");
        assertNull(
                ((DockerSandboxState) client.deserializeState(legacyJson))
                        .getOutputTruncateBytes());
    }

    @Test
    void sandboxResolvesConfiguredCapAndFallsBackToDefault() throws Exception {
        assertEquals(DEFAULT_CAP, resolveCap(minimalState()));

        DockerSandboxState configured = minimalState();
        configured.setOutputTruncateBytes(2 * 1024 * 1024);
        assertEquals(2 * 1024 * 1024, resolveCap(configured));

        // Hand-edited state bypasses the options-side validation; the resolver must not
        // poison readStream with a non-positive buffer size.
        DockerSandboxState tampered = minimalState();
        tampered.setOutputTruncateBytes(0);
        assertEquals(DEFAULT_CAP, resolveCap(tampered));
    }

    @Test
    void readStreamTruncatesAtConfiguredCap() throws Exception {
        byte[] payload = "abcdefgh".getBytes(StandardCharsets.UTF_8);

        assertEquals("abcd", readStream(new ByteArrayInputStream(payload), 4));
        assertEquals("abcdefgh", readStream(new ByteArrayInputStream(payload), 32));
    }
}
