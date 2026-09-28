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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

class E2bEnvdProcessClientTest {

    @Test
    void jsonCodecUsesConnectJsonAndJsonPayload() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));

        byte[] envelope = client.encodeStartRequestEnvelope("echo hello", "/workspace");

        assertEquals(MediaType.get("application/connect+json"), client.connectMediaType());
        assertEquals(0x00, envelope[0] & 0xFF);
        int len = ByteBuffer.wrap(envelope, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        byte[] payload = java.util.Arrays.copyOfRange(envelope, 5, 5 + len);
        String json = new String(payload, StandardCharsets.UTF_8);
        assertEquals(
                "{\"process\":{\"cmd\":\"/bin/bash\",\"args\":[\"-l\",\"-c\","
                        + "\"echo hello\"],\"cwd\":\"/workspace\"},\"stdin\":false}",
                json);
    }

    @Test
    void protoCodecKeepsBinaryPayload() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));

        byte[] envelope = client.encodeStartRequestEnvelope("echo hello", "/workspace");
        DynamicMessage expected = client.buildStartRequest("echo hello", "/workspace");

        assertEquals(MediaType.get("application/connect+proto"), client.connectMediaType());
        int len = ByteBuffer.wrap(envelope, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        byte[] payload = java.util.Arrays.copyOfRange(envelope, 5, 5 + len);
        assertArrayEquals(expected.toByteArray(), payload);
    }

    @Test
    void jsonCodecParsesStartResponseFrame() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        DynamicMessage response = dataResponse(client, "hello\n", null);
        String json = responseJson(base64("hello\n"), null, null);

        DynamicMessage parsed =
                client.parseStartResponseFrame(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(response, parsed);
    }

    @Test
    void jsonCodecReturnsEmptyMessageForNullOrMissingEvent() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));

        assertTrue(
                client.parseStartResponseFrame("null".getBytes(StandardCharsets.UTF_8))
                        .getAllFields()
                        .isEmpty());
        assertTrue(
                client.parseStartResponseFrame("{}".getBytes(StandardCharsets.UTF_8))
                        .getAllFields()
                        .isEmpty());
        assertTrue(
                client.parseStartResponseFrame("{\"event\":null}".getBytes(StandardCharsets.UTF_8))
                        .getAllFields()
                        .isEmpty());
    }

    @Test
    void jsonCodecSkipsMalformedBase64AndKeepsStreaming() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit =
                drainStartStream(
                        client,
                        connectFrames(
                                responseJson("%%%bad-base64%%%", null, null),
                                responseJson(null, base64("stderr\n"), null),
                                responseJson(null, null, 7)),
                        stdout,
                        stderr);

        assertEquals(7, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("stderr\n", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonCodecRejectsEofBeforeEnd() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        IOException exception =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        connectFrames(
                                                responseJson(base64("hello\n"), null, null),
                                                responseJson(null, base64("warn\n"), null)),
                                        stdout,
                                        stderr));

        assertTrue(exception.getMessage().contains("before receiving a process exit code"));
        assertEquals("hello\n", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("warn\n", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonCodecRejectsTruncatedFrameLengthBeforeEnd() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] truncatedLength = new byte[] {0x00, 0x00, 0x00};

        IOException exception =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        truncatedLength,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));

        assertTrue(exception.getMessage().contains("before receiving a process exit code"));
    }

    @Test
    void jsonCodecRejectsTruncatedFramePayloadBeforeEnd() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteBuffer truncatedPayload = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN);
        truncatedPayload.put((byte) 0x00);
        truncatedPayload.putInt(4);
        truncatedPayload.put((byte) '{');
        truncatedPayload.put((byte) '}');

        IOException exception =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        truncatedPayload.array(),
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));

        assertTrue(exception.getMessage().contains("before receiving a process exit code"));
    }

    @Test
    void jsonCodecReturnsEmptyOutputsWhenOnlyEndPresent() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit =
                drainStartStream(client, connectFrame(responseJson(null, null, 5)), stdout, stderr);

        assertEquals(5, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonCodecPreservesZeroExitCode() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit =
                drainStartStream(client, connectFrame(responseJson(null, null, 0)), stdout, stderr);

        assertEquals(0, exit);
    }

    private static DynamicMessage dataResponse(
            E2bEnvdProcessClient client, String stdout, String stderr) {
        Descriptors.FileDescriptor fd = client.fileDescriptor();
        Descriptors.Descriptor processEventDesc = fd.findMessageTypeByName("ProcessEvent");
        Descriptors.Descriptor dataDesc = processEventDesc.findNestedTypeByName("DataEvent");

        DynamicMessage data =
                DynamicMessage.newBuilder(dataDesc)
                        .setField(
                                stdout != null
                                        ? dataDesc.findFieldByName("stdout")
                                        : dataDesc.findFieldByName("stderr"),
                                ByteString.copyFromUtf8(stdout != null ? stdout : stderr))
                        .build();
        DynamicMessage event =
                DynamicMessage.newBuilder(processEventDesc)
                        .setField(processEventDesc.findFieldByName("data"), data)
                        .build();
        Descriptors.Descriptor startResponseDesc = fd.findMessageTypeByName("StartResponse");
        return DynamicMessage.newBuilder(startResponseDesc)
                .setField(startResponseDesc.findFieldByName("event"), event)
                .build();
    }

    private static int drainStartStream(
            E2bEnvdProcessClient client,
            byte[] connectFrame,
            ByteArrayOutputStream stdout,
            ByteArrayOutputStream stderr)
            throws Exception {
        Method method =
                E2bEnvdProcessClient.class.getDeclaredMethod(
                        "drainStartStream",
                        InputStream.class,
                        ByteArrayOutputStream.class,
                        ByteArrayOutputStream.class);
        method.setAccessible(true);
        try {
            return (int)
                    method.invoke(client, new ByteArrayInputStream(connectFrame), stdout, stderr);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static byte[] connectFrame(String json) {
        return E2bEnvdProcessClient.encodeUnaryEnvelope(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] connectFrames(String... jsons) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String json : jsons) {
            byte[] frame = connectFrame(json);
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    private static String responseJson(String stdout, String stderr, Integer exitCode) {
        StringBuilder json = new StringBuilder("{\"event\":{");
        boolean needComma = false;
        if (stdout != null || stderr != null) {
            json.append("\"data\":{");
            boolean needDataComma = false;
            if (stdout != null) {
                json.append("\"stdout\":\"").append(stdout).append("\"");
                needDataComma = true;
            }
            if (stderr != null) {
                if (needDataComma) {
                    json.append(',');
                }
                json.append("\"stderr\":\"").append(stderr).append("\"");
            }
            json.append('}');
            needComma = true;
        }
        if (exitCode != null) {
            if (needComma) {
                json.append(',');
            }
            json.append("\"end\":{\"exitCode\":").append(exitCode).append('}');
        }
        json.append("}}");
        return json.toString();
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static E2bSandboxClientOptions options(E2bCodec codec) {
        E2bSandboxClientOptions options = new E2bSandboxClientOptions();
        options.setCodec(codec);
        return options;
    }

    @Test
    void requestCarriesConnectTimeoutMsHeader() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        Interceptor capture =
                chain -> {
                    header.set(chain.request().header("Connect-Timeout-Ms"));
                    throw new SocketTimeoutException("timeout");
                };
        E2bEnvdProcessClient client = clientWithInterceptor(capture);

        assertThrows(
                SandboxException.ExecTimeoutException.class,
                () -> client.runShell(state(), "/workspace", "sleep 1000", 3));
        assertEquals("3000", header.get());
    }

    @Test
    void clientBackupToleratesServerKillMargin() throws Exception {
        // timeout=1 with a 1.5s stall followed by a clean exit frame: without the
        // client-side slack the callTimeout would fire first; with it the stalled
        // call completes normally.
        byte[] exitFrame = connectFrame(responseJson(null, null, 0));
        Interceptor stalling =
                chain -> {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException("interrupted stall");
                    }
                    return cannedResponse(chain, exitFrame);
                };
        E2bSandboxClientOptions opt = options(E2bCodec.JSON);
        opt.setHttpClient(new OkHttpClient.Builder().addInterceptor(stalling).build());
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(opt);

        client.runShell(state(), "/workspace", "echo hi", 1);
    }

    @Test
    void interruptionIsRethrownNotWrappedAsTimeout() throws Exception {
        Interceptor interrupting =
                chain -> {
                    Thread.currentThread().interrupt();
                    throw new SocketTimeoutException("read timed out");
                };
        E2bEnvdProcessClient client = clientWithInterceptor(interrupting);
        try {
            SocketTimeoutException thrown =
                    assertThrows(
                            SocketTimeoutException.class,
                            () -> client.runShell(state(), "/workspace", "sleep 1000", 3));
            assertEquals("read timed out", thrown.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt bit must be restored");
        } finally {
            // Do not leak the interrupt bit into other tests.
            Thread.interrupted();
        }
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void nonPositiveTimeoutFailsFastWithoutRequest() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        Interceptor capture =
                chain -> {
                    header.set(chain.request().header("Connect-Timeout-Ms"));
                    throw new SocketTimeoutException("must not be called");
                };
        E2bEnvdProcessClient client = clientWithInterceptor(capture);

        IllegalArgumentException zero =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.runShell(state(), "/workspace", "echo hi", 0));
        assertTrue(zero.getMessage().contains("must be positive"));
        assertTrue(zero.getMessage().contains("caller bug"));
        IllegalArgumentException negative =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.runShell(state(), "/workspace", "echo hi", -5));
        assertTrue(negative.getMessage().contains("-5"));
        assertNull(header.get(), "no HTTP request must be issued for invalid timeout");
    }

    @Test
    void jsonDeadlineExceededEndFrameMapsToExecTimeout() throws Exception {
        byte[] payload =
                ("{\"error\":{\"code\":\"deadline_exceeded\","
                                + "\"message\":\"context deadline exceeded\"}}")
                        .getBytes(StandardCharsets.UTF_8);
        E2bEnvdProcessClient client =
                clientWithBody(options(E2bCodec.JSON), endStreamFrame(payload));

        assertThrows(
                SandboxException.ExecTimeoutException.class,
                () -> client.runShell(state(), "/workspace", "sleep 1000", 30));
    }

    @Test
    void timeoutMessageCarriesNoCommandText() throws Exception {
        byte[] payload =
                ("{\"error\":{\"code\":\"deadline_exceeded\","
                                + "\"message\":\"context deadline exceeded\"}}")
                        .getBytes(StandardCharsets.UTF_8);
        E2bEnvdProcessClient client =
                clientWithBody(options(E2bCodec.JSON), endStreamFrame(payload));

        SandboxException.ExecTimeoutException e =
                assertThrows(
                        SandboxException.ExecTimeoutException.class,
                        () ->
                                client.runShell(
                                        state(),
                                        "/workspace",
                                        "curl -H 'Authorization: Bearer sk-secret' example.test",
                                        30));
        assertEquals("Command timed out after 30s", e.getMessage());
        assertFalse(
                e.getMessage().contains("sk-secret"),
                "command text must not leak into the message");
    }

    @Test
    void jsonNonTimeoutErrorFrameKeepsStreamError() throws Exception {
        byte[] payload =
                "{\"error\":{\"code\":\"unavailable\",\"message\":\"sandbox gone\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        E2bEnvdProcessClient client =
                clientWithBody(options(E2bCodec.JSON), endStreamFrame(payload));

        IOException e =
                assertThrows(
                        IOException.class,
                        () -> client.runShell(state(), "/workspace", "sleep 1000", 30));
        assertTrue(e.getMessage().contains("unavailable"), "code must survive: " + e.getMessage());
    }

    @Test
    void protoCodecStillDecodesJsonEndStreamFrame() throws Exception {
        // Captured from a live envd (envd 0.6.10): with Content-Type
        // application/connect+proto the end-stream payload is still this exact
        // 76-byte JSON blob — connect-go hardcodes json.Marshal in MarshalEndStream.
        // Guards against reintroducing a codec-dispatched (protobuf) decoder.
        String live =
                "{\"error\":{\"code\":\"deadline_exceeded\","
                        + "\"message\":\"context deadline exceeded\"}}";
        assertEquals(76, live.getBytes(StandardCharsets.UTF_8).length);
        E2bEnvdProcessClient client =
                clientWithBody(
                        options(E2bCodec.PROTO),
                        endStreamFrame(live.getBytes(StandardCharsets.UTF_8)));

        assertThrows(
                SandboxException.ExecTimeoutException.class,
                () -> client.runShell(state(), "/workspace", "sleep 1000", 30));
    }

    @Test
    void cleanEndFrameWithoutExitStaysStreamError() throws Exception {
        E2bEnvdProcessClient client =
                clientWithBody(
                        options(E2bCodec.JSON),
                        endStreamFrame("{}".getBytes(StandardCharsets.UTF_8)));

        IOException e =
                assertThrows(
                        IOException.class,
                        () -> client.runShell(state(), "/workspace", "sleep 1000", 30));
        assertTrue(
                e.getMessage().contains("before receiving a process exit code"),
                "clean end without exit keeps #2828 semantics: " + e.getMessage());
    }

    @Test
    void alreadyCancelledCallFailsFastWithoutBlocking() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        Interceptor capture =
                chain -> {
                    header.set(chain.request().header("Connect-Timeout-Ms"));
                    throw new SocketTimeoutException("must not be called");
                };
        E2bEnvdProcessClient client = clientWithInterceptor(capture);
        Thread.currentThread().interrupt();
        try {
            long start = System.nanoTime();
            InterruptedIOException e =
                    assertThrows(
                            InterruptedIOException.class,
                            () -> client.runShell(state(), "/workspace", "echo hi", 30));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMs < 5000, "must fail fast, took " + elapsedMs + "ms");
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt bit must be restored");
            assertTrue(
                    e.getMessage().contains("cancelled before start"),
                    "must not look like a timeout: " + e.getMessage());
        } finally {
            Thread.interrupted();
        }
        assertNull(header.get(), "no HTTP request must be issued for a cancelled call");
    }

    @Test
    void malformedEndFrameFallsThroughToStreamError() throws Exception {
        E2bEnvdProcessClient client =
                clientWithBody(
                        options(E2bCodec.JSON),
                        endStreamFrame("%%%not-json%%%".getBytes(StandardCharsets.UTF_8)));

        IOException e =
                assertThrows(
                        IOException.class,
                        () -> client.runShell(state(), "/workspace", "sleep 1000", 30));
        assertTrue(
                e.getMessage().contains("before receiving a process exit code"),
                "unparsable end frame keeps #2828 semantics: " + e.getMessage());
    }

    @Test
    void emptyEndFrameStaysStreamError() throws Exception {
        E2bEnvdProcessClient client =
                clientWithBody(options(E2bCodec.PROTO), endStreamFrame(new byte[0]));

        IOException e =
                assertThrows(
                        IOException.class,
                        () -> client.runShell(state(), "/workspace", "sleep 1000", 30));
        assertTrue(
                e.getMessage().contains("before receiving a process exit code"),
                "clean end without exit keeps #2828 semantics: " + e.getMessage());
    }

    @Test
    void prematureEndAfterDeadlineMapsToExecTimeout() {
        E2bEnvdProcessClient.MissingExitCodeException e =
                new E2bEnvdProcessClient.MissingExitCodeException();

        Exception mapped =
                E2bEnvdProcessClient.mapPrematureEnd(
                        e, "sleep 1000", 5, System.nanoTime() - 6_000_000_000L);

        assertInstanceOf(SandboxException.ExecTimeoutException.class, mapped);
    }

    @Test
    void prematureEndBeforeDeadlineStaysStreamError() {
        E2bEnvdProcessClient.MissingExitCodeException e =
                new E2bEnvdProcessClient.MissingExitCodeException();

        assertSame(e, E2bEnvdProcessClient.mapPrematureEnd(e, "sleep 1000", 30, System.nanoTime()));
    }

    private static E2bEnvdProcessClient clientWithInterceptor(Interceptor interceptor)
            throws Exception {
        E2bSandboxClientOptions opt = options(E2bCodec.PROTO);
        opt.setHttpClient(new OkHttpClient.Builder().addInterceptor(interceptor).build());
        return new E2bEnvdProcessClient(opt);
    }

    private static E2bEnvdProcessClient clientWithBody(E2bSandboxClientOptions opt, byte[] body)
            throws Exception {
        Interceptor canned = chain -> cannedResponse(chain, body);
        opt.setHttpClient(new OkHttpClient.Builder().addInterceptor(canned).build());
        return new E2bEnvdProcessClient(opt);
    }

    private static Response cannedResponse(Interceptor.Chain chain, byte[] body) {
        return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, null))
                .build();
    }

    private static byte[] endStreamFrame(byte[] payload) {
        byte[] out = new byte[5 + payload.length];
        out[0] = 0x02;
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(payload.length);
        System.arraycopy(payload, 0, out, 5, payload.length);
        return out;
    }

    private static E2bSandboxState state() {
        E2bSandboxState state = new E2bSandboxState();
        state.setSandboxId("test-sandbox");
        return state;
    }
}
