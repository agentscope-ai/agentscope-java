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

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Socket-level timeout coverage against a real HTTP stack.
 *
 * <p>These exist because application-level interceptors that throw short-circuit before
 * OkHttp's timeout machinery engages, so {@code E2bEnvdProcessClientTest} cannot prove
 * {@code readTimeout(0)} or the client backup timing. {@code envdHost} hardcodes the envd
 * address, so a URL-rewriting interceptor points it at a local server while still going
 * through the real network stack — the timeouts under test remain enforced by OkHttp itself.
 */
class E2bEnvdProcessClientSocketTest {

    private static final int TIMEOUT_SECONDS = 1;
    private static final int BASE_READ_TIMEOUT_SECONDS = 1;

    private MockWebServer server;
    private E2bEnvdProcessClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();

        // Rewrite only the destination; the call still travels the real stack, so
        // callTimeout and readTimeout behave exactly as they do against envd.
        Interceptor redirectToLocalServer =
                chain -> {
                    Request original = chain.request();
                    HttpUrl rewritten =
                            original.url()
                                    .newBuilder()
                                    .scheme("http")
                                    .host(server.getHostName())
                                    .port(server.getPort())
                                    .build();
                    return chain.proceed(original.newBuilder().url(rewritten).build());
                };

        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(redirectToLocalServer)
                        // Equal to T on purpose: if the per-call client inherited the base
                        // read timeout, an idle socket would be killed at ~T with a
                        // SocketTimeoutException instead of surviving to the client backup.
                        .readTimeout(BASE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build();

        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setCodec(E2bCodec.PROTO);
        opt.setHttpClient(http);
        client = new E2bEnvdProcessClient(opt);
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdown();
    }

    @Test
    void idleSocketIsNotKilledByInheritedReadTimeout() throws Exception {
        // A start event arrives, then the socket goes silent past T. With readTimeout(0) the
        // call survives the gap and ends on the client backup (T + 5s slack). With the base
        // read timeout inherited, it would die at ~1s as a SocketTimeoutException.
        server.enqueue(stalledResponse(startFrame(4242)));

        long start = System.nanoTime();
        SandboxException.ExecTimeoutException e =
                assertThrows(
                        SandboxException.ExecTimeoutException.class,
                        () -> client.runShell(state(), "/workspace", "echo hi", TIMEOUT_SECONDS));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals("Command timed out after " + TIMEOUT_SECONDS + "s", e.getMessage());
        // Survived past T: only the T+slack client backup could end this call, which means the
        // idle socket was not killed by an inherited read timeout.
        assertTrue(
                elapsedMs >= TIMEOUT_SECONDS * 1000L,
                "call must outlive T, got " + elapsedMs + "ms");
        assertTrue(
                !(e.getCause() instanceof SocketTimeoutException),
                "must not be an inherited read timeout");
    }

    @Test
    void clientBackupFailsafeEndsHungSocket() throws Exception {
        // The server accepts the request and never responds at all. The client backup
        // (T + 5s slack) must still produce a bounded, correctly-typed failure.
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        long start = System.nanoTime();
        assertThrows(
                SandboxException.ExecTimeoutException.class,
                () -> client.runShell(state(), "/workspace", "sleep 1000", TIMEOUT_SECONDS));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs >= TIMEOUT_SECONDS * 1000L, "returned before T: " + elapsedMs + "ms");
        assertTrue(elapsedMs < 30_000, "client backup did not bound the call: " + elapsedMs + "ms");
    }

    @Test
    void normalStreamOverRealSocketSucceeds() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/connect+proto")
                        .setBody(new Buffer().write(dataFrame(protoExitEvent(0)))));

        ExecResult r = client.runShell(state(), "/workspace", "echo hi", 30);

        assertEquals(0, r.exitCode());
        assertEquals("", r.stdout());
    }

    private static MockResponse stalledResponse(byte[] partialBody) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/connect+proto")
                .setBody(new Buffer().write(partialBody))
                .setSocketPolicy(SocketPolicy.NO_RESPONSE);
    }

    private static byte[] startFrame(int pid) {
        return dataFrame(
                ("{\"event\":{\"start\":{\"pid\":" + pid + "}}}").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A PROTO-encoded {@code StartResponse} carrying the end event, built from the client's own
     * descriptors instead of hand-rolled wire bytes.
     */
    private byte[] protoExitEvent(int exitCode) {
        Descriptors.FileDescriptor fd = client.fileDescriptor();
        Descriptors.Descriptor startResponseDesc = fd.findMessageTypeByName("StartResponse");
        Descriptors.Descriptor processEventDesc = fd.findMessageTypeByName("ProcessEvent");
        Descriptors.Descriptor endDesc = processEventDesc.findNestedTypeByName("EndEvent");

        DynamicMessage end =
                DynamicMessage.newBuilder(endDesc)
                        .setField(endDesc.findFieldByName("exit_code"), exitCode)
                        .build();
        DynamicMessage event =
                DynamicMessage.newBuilder(processEventDesc)
                        .setField(processEventDesc.findFieldByName("end"), end)
                        .build();
        return DynamicMessage.newBuilder(startResponseDesc)
                .setField(startResponseDesc.findFieldByName("event"), event)
                .build()
                .toByteArray();
    }

    private static byte[] dataFrame(byte[] payload) {
        return frame(0x00, payload);
    }

    private static byte[] frame(int flags, byte[] payload) {
        byte[] out = new byte[5 + payload.length];
        out[0] = (byte) flags;
        out[1] = (byte) (payload.length >>> 24);
        out[2] = (byte) (payload.length >>> 16);
        out[3] = (byte) (payload.length >>> 8);
        out[4] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 5, payload.length);
        return out;
    }

    private static E2bSandboxState state() {
        E2bSandboxState state = new E2bSandboxState();
        state.setSandboxId("socket-test-sandbox");
        return state;
    }
}
