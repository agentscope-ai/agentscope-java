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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import okhttp3.MediaType;
import org.junit.jupiter.api.Test;

class E2bEnvdProcessClientTest {

    @Test
    void jsonCodecUsesConnectJsonAndJsonPayload() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));

        byte[] envelope = client.encodeStartRequestEnvelope("echo hello", "/workspace");

        assertEquals(MediaType.get("application/connect+json"), client.connectMediaType());
        assertEquals(0x00, envelope[0] & 0xFF);
        int len = ByteBuffer.wrap(envelope, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        byte[] payload = Arrays.copyOfRange(envelope, 5, 5 + len);
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
        byte[] payload = Arrays.copyOfRange(envelope, 5, 5 + len);
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
    void jsonCodecReturnsSentinelWhenEndMissing() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit =
                drainStartStream(
                        client,
                        connectFrames(
                                responseJson(base64("hello\n"), null, null),
                                responseJson(null, base64("warn\n"), null)),
                        stdout,
                        stderr);

        assertEquals(Integer.MIN_VALUE, exit);
        assertEquals("hello\n", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("warn\n", stderr.toString(StandardCharsets.UTF_8));
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

    private static DynamicMessage endEventResponse(
            E2bEnvdProcessClient client, Integer exitCode, String error) {
        Descriptors.FileDescriptor fd = client.fileDescriptor();
        Descriptors.Descriptor processEventDesc = fd.findMessageTypeByName("ProcessEvent");
        Descriptors.Descriptor endDesc = processEventDesc.findNestedTypeByName("EndEvent");

        DynamicMessage.Builder endBuilder = DynamicMessage.newBuilder(endDesc);
        if (exitCode != null) {
            endBuilder.setField(endDesc.findFieldByName("exit_code"), exitCode);
        }
        if (error != null) {
            endBuilder.setField(endDesc.findFieldByName("error"), error);
        }
        DynamicMessage end = endBuilder.build();

        DynamicMessage event =
                DynamicMessage.newBuilder(processEventDesc)
                        .setField(processEventDesc.findFieldByName("end"), end)
                        .build();
        Descriptors.Descriptor startResponseDesc = fd.findMessageTypeByName("StartResponse");
        return DynamicMessage.newBuilder(startResponseDesc)
                .setField(startResponseDesc.findFieldByName("event"), event)
                .build();
    }

    // Connect protocol: final envelope (flags bit 1) is EndStreamMessage JSON.
    // Python SDK ServerStreamParser: if "error" in data → raise make_error(data["error"])
    // https://connectrpc.com/docs/protocol/#error-end-stream
    // https://github.com/e2b-dev/e2b/blob/main/packages/python-sdk/e2b_connect/client.py#L230-L238
    @Test
    void endStreamResponseWithErrorThrows() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        String errorJson = "{\"error\":{\"code\":\"internal\",\"message\":\"process crash\"}}";
        byte[] frame = endStreamFrame(errorJson);

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("internal"));
        assertTrue(ex.getMessage().contains("process crash"));
    }

    // Connect protocol: successful EndStreamResponse is "{}" (no error, no metadata).
    // Python SDK: end_stream without "error" → return (stop iteration).
    @Test
    void endStreamResponseWithoutErrorBreaksCleanly() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        connectFrame(responseJson(base64("hello"), null, null)),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(Integer.MIN_VALUE, exit);
        assertEquals("hello", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    // Proto3: sint32 exit_code defaults to 0, omitted from JSON when 0.
    // getField() returns proto3 default (0) when absent — hasField() returns false.
    // Python SDK reads event.event.end.exit_code directly (always returns 0 by default).
    // https://protobuf.dev/programming-guides/proto3/#default
    @Test
    void exitCodeZeroDefaultWhenOmitted() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = concatFrames(connectFrame(endEventJson(null, null)), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(0, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void exitCodeNonZero() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = concatFrames(connectFrame(endEventJson(42, null)), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(42, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    // EndEvent.error — populated when cmd.Wait() returns ExitError
    // (non-zero exit code or signal). Go handler: Error = errMsg.
    // https://github.com/e2b-dev/infra/blob/main/packages/envd/internal/services/process/handler/handler.go
    // Python SDK: CommandResult(error=event.event.end.error)
    // https://github.com/e2b-dev/e2b/blob/main/packages/python-sdk/e2b/sandbox_sync/commands/command_handle.py#L115
    @Test
    void endEventErrorWrittenToStderr() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(connectFrame(endEventJson(null, "signal: 9")), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(0, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("signal: 9"));
    }

    // Connect protocol: flags bit 0 = compressed (not supported by this client).
    // https://connectrpc.com/docs/protocol/#streaming-rpcs
    @Test
    void compressedFrameThrowsIOException() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = envelope(0x01, "{}".getBytes(StandardCharsets.UTF_8));

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertTrue(ex.getMessage().contains("Compressed"));
    }

    // Connect protocol: flags bits 2-7 reserved — implementations must skip unless understood.
    // https://connectrpc.com/docs/protocol/#streaming-rpcs
    @Test
    void reservedFlagsFrameIsSkipped() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        envelope(0x04, "ignored".getBytes(StandardCharsets.UTF_8)),
                        connectFrame(endEventJson(3, null)),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(3, exit);
    }

    // ---- PROTO codec tests ----

    @Test
    void protoCodecDrainStartStream() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));
        DynamicMessage dataResp = dataResponse(client, "hello\n", null);
        byte[] frame = concatFrames(connectFrame(dataResp.toByteArray()), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(Integer.MIN_VALUE, exit);
        assertEquals("hello\n", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void protoCodecDrainStartStreamWithEndEvent() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));
        DynamicMessage dataResp = dataResponse(client, null, "err\n");
        DynamicMessage endResp = endEventResponse(client, 42, "oops");
        byte[] frame =
                concatFrames(
                        connectFrame(dataResp.toByteArray()),
                        connectFrame(endResp.toByteArray()),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(42, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("oops"));
    }

    @Test
    void protoCodecDrainStartStreamWithEndOnly() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));
        DynamicMessage endResp = endEventResponse(client, 7, null);
        byte[] frame = concatFrames(connectFrame(endResp.toByteArray()), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(7, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    // ---- drainStartStream edge cases ----

    @Test
    void illegalFrameLengthThrowsIOException() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = new byte[5];
        frame[0] = 0x00;
        ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(64 * 1024 * 1024 + 1);

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertTrue(ex.getMessage().contains("Invalid connect frame length"));
    }

    @Test
    void truncatedFrameDataBreaks() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = new byte[10];
        frame[0] = 0x00;
        ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(100);
        System.arraycopy("hello".getBytes(StandardCharsets.UTF_8), 0, frame, 5, 5);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(Integer.MIN_VALUE, exit);
    }

    @Test
    void zeroLengthFrameIsSkipped() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        envelope(0x00, new byte[0]),
                        connectFrame(endEventJson(99, null)),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(99, exit);
    }

    // ---- parseJsonStartResponse edge cases ----

    @Test
    void jsonCodecNonIntegerExitCodeIsIgnored() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        String json = "{\"event\":{\"end\":{\"exitCode\":\"abc\"}}}";
        byte[] frame = concatFrames(connectFrame(json), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        // exitCode defaults to 0 (proto3 default) when not convertible to int
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(0, exit);
    }

    @Test
    void jsonCodecNonTextualErrorIsIgnored() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        String json = "{\"event\":{\"end\":{\"exitCode\":1,\"error\":123}}}";
        byte[] frame = concatFrames(connectFrame(json), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(1, exit);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
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
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private static byte[] envelope(int flags, byte[] payload) {
        byte[] out = new byte[5 + payload.length];
        out[0] = (byte) flags;
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(payload.length);
        System.arraycopy(payload, 0, out, 5, payload.length);
        return out;
    }

    private static byte[] connectFrame(String json) {
        return E2bEnvdProcessClient.encodeUnaryEnvelope(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] connectFrame(byte[] payload) {
        return envelope(0x00, payload);
    }

    private static byte[] endStreamFrame(String json) {
        return envelope(0x02, json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] concatFrames(byte[]... frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] frame : frames) {
            out.writeBytes(frame);
        }
        return out.toByteArray();
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

    private static String endEventJson(Integer exitCode, String error) {
        StringBuilder json = new StringBuilder("{\"event\":{\"end\":{");
        boolean needComma = false;
        if (exitCode != null) {
            json.append("\"exitCode\":").append(exitCode);
            needComma = true;
        }
        if (error != null) {
            if (needComma) {
                json.append(',');
            }
            json.append("\"error\":\"").append(error).append("\"");
        }
        json.append("}}}");
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

    // ---- static utility method tests ----

    @Test
    void filesystemHostWithCustomDomain() throws Exception {
        Method method =
                E2bEnvdProcessClient.class.getDeclaredMethod(
                        "filesystemHost", E2bSandboxState.class);
        method.setAccessible(true);

        E2bSandboxState state = mock(E2bSandboxState.class);
        when(state.getSandboxDomain()).thenReturn("custom.com");

        String result = (String) method.invoke(null, state);
        assertEquals("https://sandbox.custom.com", result);
    }

    @Test
    void filesystemHostWithDefaultDomain() throws Exception {
        Method method =
                E2bEnvdProcessClient.class.getDeclaredMethod(
                        "filesystemHost", E2bSandboxState.class);
        method.setAccessible(true);

        E2bSandboxState state = mock(E2bSandboxState.class);
        when(state.getSandboxDomain()).thenReturn(null);

        String result = (String) method.invoke(null, state);
        assertEquals("https://sandbox.e2b.app", result);
    }

    @Test
    void filenameFromPathWithSlashReturnsBasename() throws Exception {
        Method method =
                E2bEnvdProcessClient.class.getDeclaredMethod("filenameFromPath", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(null, "/home/user/file.txt");
        assertEquals("file.txt", result);
    }

    @Test
    void filenameFromPathWithoutSlashReturnsInput() throws Exception {
        Method method =
                E2bEnvdProcessClient.class.getDeclaredMethod("filenameFromPath", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(null, "file.txt");
        assertEquals("file.txt", result);
    }

    @Test
    void filenameFromPathWithTrailingSlashReturnsEmpty() throws Exception {
        Method method =
                E2bEnvdProcessClient.class.getDeclaredMethod("filenameFromPath", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(null, "/home/");
        assertEquals("", result);
    }
}
