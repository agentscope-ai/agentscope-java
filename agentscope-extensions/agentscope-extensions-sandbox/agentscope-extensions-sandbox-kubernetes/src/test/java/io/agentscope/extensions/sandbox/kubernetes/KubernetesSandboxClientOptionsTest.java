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
package io.agentscope.extensions.sandbox.kubernetes;

import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KubernetesSandboxClientOptionsTest {

    // -- KubernetesSandboxClientOptions --

    @Test
    void environmentDefaultsToEmpty() {
        KubernetesSandboxClientOptions opts = new KubernetesSandboxClientOptions();
        Assertions.assertNotNull(opts.getEnvironment());
        Assertions.assertTrue(opts.getEnvironment().isEmpty());
    }

    @Test
    void setEnvironmentDefensivelyCopies() {
        KubernetesSandboxClientOptions opts = new KubernetesSandboxClientOptions();
        Map<String, String> original = new java.util.HashMap<>();
        original.put("K", "V");
        opts.setEnvironment(original);
        original.put("K2", "V2");
        Assertions.assertFalse(
                opts.getEnvironment().containsKey("K2"),
                "setEnvironment should copy, not retain original reference");
    }

    @Test
    void setEnvironmentNullClearsMap() {
        KubernetesSandboxClientOptions opts = new KubernetesSandboxClientOptions();
        opts.setEnvironment(Map.of("K", "V"));
        opts.setEnvironment(null);
        Assertions.assertTrue(opts.getEnvironment().isEmpty());
    }

    @Test
    void getEnvironmentIsUnmodifiable() {
        KubernetesSandboxClientOptions opts = new KubernetesSandboxClientOptions();
        opts.setEnvironment(Map.of("K", "V"));
        Assertions.assertThrows(
                UnsupportedOperationException.class, () -> opts.getEnvironment().put("X", "Y"));
    }

    // -- KubernetesSandboxClient merge / copy --

    @Test
    void mergeNoCallOptionsPreservesSpecEnv() {
        KubernetesSandboxClient client = clientWithSpecEnv(Map.of("SPEC_KEY", "spec_val"));
        KubernetesSandboxClientOptions merged = client.merge(null);
        Assertions.assertEquals("spec_val", merged.getEnvironment().get("SPEC_KEY"));
    }

    @Test
    void mergeEmptyCallEnvPreservesSpecEnv() {
        KubernetesSandboxClient client = clientWithSpecEnv(Map.of("SPEC_KEY", "spec_val"));
        KubernetesSandboxClientOptions call = new KubernetesSandboxClientOptions();
        KubernetesSandboxClientOptions merged = client.merge(call);
        Assertions.assertEquals("spec_val", merged.getEnvironment().get("SPEC_KEY"));
    }

    @Test
    void mergeCallEnvOverridesSpecEnv() {
        KubernetesSandboxClient client = clientWithSpecEnv(Map.of("KEY", "spec_val"));
        KubernetesSandboxClientOptions call = new KubernetesSandboxClientOptions();
        call.setEnvironment(Map.of("KEY", "call_val"));
        KubernetesSandboxClientOptions merged = client.merge(call);
        Assertions.assertEquals(
                "call_val",
                merged.getEnvironment().get("KEY"),
                "call-level env should override spec-level env for same key");
    }

    @Test
    void mergeCallEnvAddsToSpecEnv() {
        KubernetesSandboxClient client = clientWithSpecEnv(Map.of("SPEC_KEY", "spec_val"));
        KubernetesSandboxClientOptions call = new KubernetesSandboxClientOptions();
        call.setEnvironment(Map.of("CALL_KEY", "call_val"));
        KubernetesSandboxClientOptions merged = client.merge(call);
        Assertions.assertEquals(
                "spec_val",
                merged.getEnvironment().get("SPEC_KEY"),
                "spec-level keys absent from call-level should be preserved");
        Assertions.assertEquals(
                "call_val",
                merged.getEnvironment().get("CALL_KEY"),
                "call-level keys should be added");
    }

    // -- helpers --

    private static KubernetesSandboxClient clientWithSpecEnv(Map<String, String> env) {
        KubernetesSandboxClientOptions spec = new KubernetesSandboxClientOptions();
        spec.setEnvironment(env);
        return new KubernetesSandboxClient(spec);
    }
}
