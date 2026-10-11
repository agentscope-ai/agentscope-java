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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
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

    // Opt-in protocol diagnostics (not a behavioral assertion suite): dumps raw
    // Connect envelopes from a live sandbox so flag layouts and EndStream payloads
    // can be inspected when envd behavior is in doubt. Asserts only the EndStream
    // flag contract (bit 1 set, bit 0 unset).
    @Test
    void captureRawConnectFrames() throws Exception {
        String apiKey = System.getenv("E2B_API_KEY");
        String templateId = System.getProperty("E2B_TEMPLATE_ID", "code-interpreter-v1");

        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setApiKey(apiKey);
        opt.setTemplateId(templateId);
        opt.setReadTimeoutSeconds(120);
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
            byte[] envelope = client.encodeStartRequestEnvelope("exit 42", "/home/user");
            String host = "https://49983-" + state.getSandboxId() + "." + state.getSandboxDomain();
            log.info("envd host: {}", host);

            OkHttpClient rawHttp =
                    new OkHttpClient.Builder()
                            .readTimeout(120, TimeUnit.SECONDS)
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .build();

            Request.Builder rb =
                    new Request.Builder()
                            .url(host + "/process.Process/Start")
                            .post(RequestBody.create(envelope, client.connectMediaType()))
                            .addHeader("Connect-Protocol-Version", "1")
                            .addHeader("User-Agent", "agentscope-java-e2b-it")
                            .addHeader("E2b-Sandbox-Id", state.getSandboxId())
                            .addHeader("E2b-Sandbox-Port", "49983")
                            .addHeader(
                                    "Authorization",
                                    "Basic "
                                            + Base64.getEncoder()
                                                    .encodeToString(
                                                            "user:"
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)));
            if (state.getEnvdAccessToken() != null && !state.getEnvdAccessToken().isBlank()) {
                rb.addHeader("X-Access-Token", state.getEnvdAccessToken());
            }

            try (Response res = rawHttp.newCall(rb.build()).execute()) {
                log.info("=== HTTP Response ===");
                log.info("Status: {} {}", res.code(), res.message());
                log.info("Headers:");
                for (int i = 0; i < res.headers().size(); i++) {
                    log.info("  {}: {}", res.headers().name(i), res.headers().value(i));
                }

                assertTrue(res.isSuccessful(), "HTTP " + res.code());

                int frameCount = 0;
                int endStreamFlags = -1;
                int endStreamPayloadLen = -1;

                InputStream in = res.body().byteStream();
                ByteArrayOutputStream rawCapture = new ByteArrayOutputStream();

                log.debug("=== Frames ===");
                while (true) {
                    int flags = in.read();
                    if (flags == -1) {
                        log.debug("Stream ended (read returned -1)");
                        break;
                    }
                    rawCapture.write(flags);

                    byte[] lenB = in.readNBytes(4);
                    if (lenB.length < 4) {
                        log.debug("Incomplete length field, exiting");
                        break;
                    }
                    rawCapture.write(lenB);

                    int len = ByteBuffer.wrap(lenB).order(ByteOrder.BIG_ENDIAN).getInt();
                    if (len < 0 || len > 64 * 1024 * 1024) {
                        log.debug("Invalid length: {}", len);
                        break;
                    }

                    byte[] data = in.readNBytes(len);
                    if (data.length < len) {
                        log.debug("Incomplete frame data, exiting");
                        break;
                    }
                    rawCapture.write(data);

                    frameCount++;
                    // Connect protocol: bit 0 (0x01) = compressed, bit 1 (0x02) = EndStream
                    // https://connectrpc.com/docs/protocol/#streaming-rpcs
                    boolean isEndStream = (flags & 0x02) != 0;
                    boolean isCompressed = (flags & 0x01) != 0;
                    boolean hasReserved = (flags & 0xFC) != 0;

                    String payloadPreview;
                    if (isEndStream && len <= 4096) {
                        payloadPreview = new String(data, StandardCharsets.UTF_8);
                    } else if (len > 0) {
                        String raw =
                                new String(
                                        data,
                                        0,
                                        Math.min(data.length, 160),
                                        StandardCharsets.UTF_8);
                        payloadPreview = raw + (len > 160 ? "..." : "") + " [" + len + " bytes]";
                    } else {
                        payloadPreview = "[empty]";
                    }

                    log.debug(
                            "Frame #{}: flags=0x{} len={} EndStream={} Compressed={} reserved={}"
                                    + " payload={}",
                            frameCount,
                            String.format("%02X", flags),
                            len,
                            isEndStream,
                            isCompressed,
                            hasReserved,
                            payloadPreview);

                    if (isEndStream) {
                        endStreamFlags = flags;
                        endStreamPayloadLen = len;
                    }
                }

                log.info("=== RESULT ===");
                log.info("Total frames: {}", frameCount);
                log.info("Raw bytes captured: {}", rawCapture.size());

                if (endStreamFlags >= 0) {
                    log.info("EndStream frame flags: 0x{}", Integer.toHexString(endStreamFlags));
                    log.info("EndStream frame payload length: {}", endStreamPayloadLen);
                    log.info("Bit 1 (0x02/EndStream) set: {}", (endStreamFlags & 0x02) != 0);
                    log.info("Bit 0 (0x01/Compressed) set: {}", (endStreamFlags & 0x01) != 0);

                    // Connect protocol: bit 1 (0x02) = EndStreamResponse
                    assertEquals(
                            0x02,
                            endStreamFlags & 0x02,
                            "Per Connect protocol, bit 1 (0x02) must be set on EndStream");
                    assertEquals(
                            0,
                            endStreamFlags & 0x01,
                            "Bit 0 (0x01) is Compressed flag, should not be set");
                } else {
                    log.warn("WARNING: No EndStream frame found");
                }
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
