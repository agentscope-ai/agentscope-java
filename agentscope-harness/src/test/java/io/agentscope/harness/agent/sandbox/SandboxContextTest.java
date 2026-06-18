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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import org.junit.jupiter.api.Test;

class SandboxContextTest {

    @Test
    void keepAliveDefaultsToFalse() {
        SandboxContext ctx = SandboxContext.builder().build();
        assertFalse(ctx.isKeepAlive());
    }

    @Test
    void keepAliveWhenSetTrueReturnsTrue() {
        SandboxContext ctx = SandboxContext.builder().keepAlive(true).build();
        assertTrue(ctx.isKeepAlive());
    }

    @Test
    void keepAliveWhenSetFalseReturnsFalse() {
        SandboxContext ctx = SandboxContext.builder().keepAlive(false).build();
        assertFalse(ctx.isKeepAlive());
    }

    @Test
    void specKeepAliveDefaultsToFalse() {
        DockerFilesystemSpec spec = new DockerFilesystemSpec();
        assertFalse(spec.isKeepAlive());
    }

    @Test
    void specKeepAliveSetAndGet() {
        DockerFilesystemSpec spec = new DockerFilesystemSpec();
        assertSame(spec, spec.keepAlive(true));
        assertTrue(spec.isKeepAlive());
    }

    @Test
    void keepAlivePropagatedFromSpecToContext() {
        DockerFilesystemSpec spec = new DockerFilesystemSpec();
        // Default is false
        assertFalse(spec.toSandboxContext().isKeepAlive());
        // After setting true
        assertTrue(spec.keepAlive(true).toSandboxContext().isKeepAlive());
    }
}
