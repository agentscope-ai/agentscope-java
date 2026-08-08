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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EnabledIfSystemProperty(named = "E2B_API_KEY", matches = ".+")
class E2bEnvdProcessClientIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(E2bEnvdProcessClientIntegrationTest.class);

    @Test
    void captureRawConnectFrames() throws Exception {
        String apiKey = System.getProperty("E2B_API_KEY");
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

        log.info(
                "sandboxId={} domain={} envdAccessToken={}",
                state.getSandboxId(),
                state.getSandboxDomain(),
                state.getEnvdAccessToken());

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
                System.out.println("\n=== HTTP Response ===");
                System.out.println("Status: " + res.code() + " " + res.message());
                System.out.println("Headers:");
                for (int i = 0; i < res.headers().size(); i++) {
                    System.out.println(
                            "  " + res.headers().name(i) + ": " + res.headers().value(i));
                }

                assertTrue(res.isSuccessful(), "HTTP " + res.code());

                int frameCount = 0;
                int endStreamFlags = -1;
                int endStreamPayloadLen = -1;

                InputStream in = res.body().byteStream();
                ByteArrayOutputStream rawCapture = new ByteArrayOutputStream();

                System.out.println("\n=== Frames ===");
                while (true) {
                    int flags = in.read();
                    if (flags == -1) {
                        System.out.println("Stream ended (read returned -1)");
                        break;
                    }
                    rawCapture.write(flags);

                    byte[] lenB = in.readNBytes(4);
                    if (lenB.length < 4) {
                        System.out.println("Incomplete length field, exiting");
                        break;
                    }
                    rawCapture.write(lenB);

                    int len = ByteBuffer.wrap(lenB).order(ByteOrder.BIG_ENDIAN).getInt();
                    if (len < 0 || len > 64 * 1024 * 1024) {
                        System.out.println("Invalid length: " + len);
                        break;
                    }

                    byte[] data = in.readNBytes(len);
                    if (data.length < len) {
                        System.out.println("Incomplete frame data, exiting");
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

                    System.out.printf("Frame #%d: flags=0x%02X  len=%d%n", frameCount, flags, len);
                    System.out.printf(
                            "  bit1(0x02/EndStream)=%b  bit0(0x01/Compressed)=%b  reserved=%b%n",
                            isEndStream, isCompressed, hasReserved);
                    System.out.println("  payload: " + payloadPreview);
                    System.out.println();

                    if (isEndStream) {
                        endStreamFlags = flags;
                        endStreamPayloadLen = len;
                    }
                }

                System.out.println("=== RESULT ===");
                System.out.println("Total frames: " + frameCount);
                System.out.println("Raw bytes captured: " + rawCapture.size());

                if (endStreamFlags >= 0) {
                    System.out.println(
                            "EndStream frame flags: 0x" + Integer.toHexString(endStreamFlags));
                    System.out.println("EndStream frame payload length: " + endStreamPayloadLen);
                    System.out.println(
                            "Bit 1 (0x02/EndStream) set: " + ((endStreamFlags & 0x02) != 0));
                    System.out.println(
                            "Bit 0 (0x01/Compressed) set: " + ((endStreamFlags & 0x01) != 0));

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
                    System.out.println("WARNING: No EndStream frame found");
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
        String apiKey = System.getProperty("E2B_API_KEY");
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

        log.info(
                "sandboxId={} domain={} envdAccessToken={}",
                state.getSandboxId(),
                state.getSandboxDomain(),
                state.getEnvdAccessToken());

        try {
            E2bEnvdProcessClient client = new E2bEnvdProcessClient(opt);

            // Thread A: run sleep 120 — blocks until killed
            ExecutorService executor = Executors.newSingleThreadExecutor();
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
                    assertThrows(ExecutionException.class, () -> future.get(180, TimeUnit.SECONDS));
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
            executor.shutdownNow();
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
