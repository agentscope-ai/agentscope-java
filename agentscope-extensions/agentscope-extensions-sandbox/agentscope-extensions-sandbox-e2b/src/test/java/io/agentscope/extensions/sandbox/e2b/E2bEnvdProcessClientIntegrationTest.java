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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Opt-in live-sandbox diagnostics: runs only when E2B_API_KEY is set in the
// environment, following the repository E2E-test convention.
@EnabledIfEnvironmentVariable(named = "E2B_API_KEY", matches = ".+")
class E2bEnvdProcessClientIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(E2bEnvdProcessClientIntegrationTest.class);

    @Test
    void signalKillViaRunShell() throws Exception {
        String apiKey = System.getenv("E2B_API_KEY");
        String templateId = System.getProperty("E2B_TEMPLATE_ID", "code-interpreter-v1");

        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setApiKey(apiKey);
        opt.setTemplateId(templateId);
        // need enough read timeout for sleep 120 + processing
        opt.setReadTimeoutSeconds(180);
        opt.setConnectTimeoutSeconds(30);

        E2bPlatformHttp platform = new E2bPlatformHttp(opt);
        E2bSandboxState state = new E2bSandboxState();
        state.setTemplateId(templateId);

        JsonNode sandboxNode = platform.createSandbox(templateId, 120);
        platform.applySandboxFields(state, sandboxNode);
        state.setSandboxDomain(opt.getDomain());

        // Never log the access token itself (bearer credential); presence only.
        log.info(
                "sandboxId={} domain={} envdAccessTokenPresent={}",
                state.getSandboxId(),
                state.getSandboxDomain(),
                state.getEnvdAccessToken() != null && !state.getEnvdAccessToken().isBlank());

        try {
            E2bEnvdProcessClient client = new E2bEnvdProcessClient(opt);

            // Thread A: run sleep 120 — blocks until killed
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> future =
                        executor.submit(
                                () -> {
                                    client.runShell(state, "/home/user", "sleep 120", 0);
                                    return null;
                                });

                // Give sleep a moment to start, then kill it with SIGKILL.
                // Kill the child sleep process (not bash) — bash exits reporting
                // 128+9=137 to Go handler. Use killall by exact name.
                Thread.sleep(3000);
                try {
                    client.runShell(state, "/home/user", "killall -9 -w sleep 2>/dev/null", 10);
                } catch (Exception e) {
                    log.warn("killall failed: {}", e.getMessage());
                    try {
                        client.runShell(
                                state,
                                "/home/user",
                                "kill -9 $(ps h -C sleep -o pid 2>/dev/null) 2>/dev/null",
                                10);
                    } catch (Exception e2) {
                        log.warn("ps kill also failed: {}", e2.getMessage());
                    }
                }

                // Thread A should terminate with ExecException (process killed by signal)
                ExecutionException ex =
                        assertThrows(
                                ExecutionException.class, () -> future.get(180, TimeUnit.SECONDS));
                Throwable cause = ex.getCause();
                assertTrue(
                        cause instanceof SandboxException,
                        "Expected SandboxException, got: "
                                + cause.getClass().getName()
                                + ": "
                                + cause.getMessage());
                SandboxException.ExecException execEx = (SandboxException.ExecException) cause;
                // Verify the process was killed by signal: EndEvent.error captured to
                // stderr (e.g. "signal: killed"), and exit code is non-zero (-1 is
                // expected from Go's ProcessState.ExitCode() when !Exited()).
                assertTrue(
                        execEx.getExitCode() != 0,
                        "Exit code should be non-zero after SIGKILL, got " + execEx.getExitCode());
                assertTrue(
                        execEx.getStderr().contains("signal: killed"),
                        "stderr should contain 'signal: killed' from EndEvent.error, got: '"
                                + execEx.getStderr()
                                + "'");
            } finally {
                executor.shutdownNow();
            }
        } finally {
            try {
                platform.killSandbox(state.getSandboxId());
                log.info("sandbox {} killed", state.getSandboxId());
            } catch (Exception e) {
                log.warn("failed to kill sandbox {}: {}", state.getSandboxId(), e.getMessage());
            }
        }
    }
}
