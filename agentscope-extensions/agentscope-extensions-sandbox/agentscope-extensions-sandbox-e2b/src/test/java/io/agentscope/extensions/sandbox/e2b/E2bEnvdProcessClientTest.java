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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
        return (int) method.invoke(client, new ByteArrayInputStream(connectFrame), stdout, stderr);
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
}
