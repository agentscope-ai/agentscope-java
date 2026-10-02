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
package io.agentscope.extensions.sandbox.agentrun;

import io.agentscope.harness.agent.sandbox.SandboxException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentRunOptionsValidationTest {

    private static AgentRunSandboxClientOptions baseValid() {
        return new AgentRunSandboxClientOptions()
                .setApiKey("test-key")
                .setTemplateName("agentscope-default")
                .setMcpServerUrl("https://example.com/mcp");
    }

    @Test
    void validPassesWithoutMounts() {
        Assertions.assertDoesNotThrow(() -> baseValid().validate());
    }

    @Test
    void missingApiKeyFails() {
        AgentRunSandboxClientOptions opt =
                new AgentRunSandboxClientOptions()
                        .setTemplateName("t")
                        .setMcpServerUrl("https://example.com/mcp");
        Assertions.assertThrows(
                SandboxException.SandboxConfigurationException.class, opt::validate);
    }

    @Test
    void missingTemplateFails() {
        AgentRunSandboxClientOptions opt =
                new AgentRunSandboxClientOptions()
                        .setApiKey("k")
                        .setMcpServerUrl("https://example.com/mcp");
        Assertions.assertThrows(
                SandboxException.SandboxConfigurationException.class, opt::validate);
    }

    @Test
    void missingMcpServerUrlFails() {
        AgentRunSandboxClientOptions opt =
                new AgentRunSandboxClientOptions().setApiKey("k").setTemplateName("t");
        Assertions.assertThrows(
                SandboxException.SandboxConfigurationException.class, opt::validate);
    }

    @Test
    void disallowedNasMountDirFails() {
        AgentRunSandboxClientOptions opt =
                baseValid()
                        .setNasConfig(
                                new AgentRunNasMountConfig()
                                        .setServerAddr("nas.example.com")
                                        .setMountDir("/opt/data"));
        SandboxException.SandboxConfigurationException ex =
                Assertions.assertThrows(
                        SandboxException.SandboxConfigurationException.class, opt::validate);
        Assertions.assertTrue(ex.getMessage().contains("/opt/data"));
    }

    @Test
    void allowedNasMountDirPasses() {
        AgentRunSandboxClientOptions opt =
                baseValid()
                        .setNasConfig(
                                new AgentRunNasMountConfig()
                                        .setServerAddr("nas.example.com")
                                        .setMountDir("/mnt/nas"));
        Assertions.assertDoesNotThrow(opt::validate);
    }

    @Test
    void disallowedOssMountDirFails() {
        AgentRunSandboxClientOptions opt =
                baseValid()
                        .addOssMount(
                                new AgentRunOssMountConfig()
                                        .setBucketName("b")
                                        .setEndpoint("oss.example.com")
                                        .setMountDir("/srv/oss"));
        SandboxException.SandboxConfigurationException ex =
                Assertions.assertThrows(
                        SandboxException.SandboxConfigurationException.class, opt::validate);
        Assertions.assertTrue(ex.getMessage().contains("/srv/oss"));
    }

    @Test
    void tooManyOssMountsFails() {
        List<AgentRunOssMountConfig> mounts = new ArrayList<>();
        for (int i = 0; i < AgentRunSandboxClientOptions.MAX_OSS_MOUNTS + 1; i++) {
            mounts.add(
                    new AgentRunOssMountConfig()
                            .setBucketName("b" + i)
                            .setEndpoint("oss.example.com")
                            .setMountDir("/home/data" + i));
        }
        AgentRunSandboxClientOptions opt = baseValid().setOssMountConfigs(mounts);
        SandboxException.SandboxConfigurationException ex =
                Assertions.assertThrows(
                        SandboxException.SandboxConfigurationException.class, opt::validate);
        Assertions.assertTrue(ex.getMessage().contains("at most"));
    }

    @Test
    void maxOssMountsPasses() {
        List<AgentRunOssMountConfig> mounts = new ArrayList<>();
        for (int i = 0; i < AgentRunSandboxClientOptions.MAX_OSS_MOUNTS; i++) {
            mounts.add(
                    new AgentRunOssMountConfig()
                            .setBucketName("b" + i)
                            .setEndpoint("oss.example.com")
                            .setMountDir("/data/m" + i));
        }
        AgentRunSandboxClientOptions opt = baseValid().setOssMountConfigs(mounts);
        Assertions.assertDoesNotThrow(opt::validate);
    }

    @Test
    void resolvedDataPlaneFromAccountAndRegion() {
        AgentRunSandboxClientOptions opt =
                baseValid().setAccountId("1234567890").setRegion("cn-hangzhou");
        Assertions.assertEquals(
                "https://1234567890.agentrun-data.cn-hangzhou.aliyuncs.com",
                opt.getResolvedDataPlaneBaseUrl());
    }

    @Test
    void resolvedDataPlaneFromExplicitOverride() {
        AgentRunSandboxClientOptions opt =
                baseValid().setDataPlaneBaseUrl("https://custom.example.com/");
        Assertions.assertEquals("https://custom.example.com", opt.getResolvedDataPlaneBaseUrl());
    }

    @Test
    void resolvedDataPlaneMissingFieldsFails() {
        AgentRunSandboxClientOptions opt = baseValid();
        Assertions.assertThrows(
                SandboxException.SandboxConfigurationException.class,
                opt::getResolvedDataPlaneBaseUrl);
    }
}
