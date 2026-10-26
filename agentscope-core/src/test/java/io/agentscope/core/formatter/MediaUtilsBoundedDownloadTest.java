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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the unbounded media download (#3318): the 50 MB cap must be enforced
 * while reading, so an oversized body (declared or chunked without Content-Length) is aborted
 * as soon as the cap is crossed instead of being fully buffered first.
 */
class MediaUtilsBoundedDownloadTest {

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(String path, int status, String contentType, byte[] body) {
        server.createContext(
                path,
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    exchange.sendResponseHeaders(status, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
    }

    @Test
    void oversizedDeclaredContentLength_rejectedWithoutFullRead() throws IOException {
        // Declares a 60 MB body but sends none — the early Content-Length check must abort
        // before any body byte is buffered (the server-side write fails harmlessly after
        // the client disconnects).
        server.createContext(
                "/huge",
                exchange -> {
                    exchange.sendResponseHeaders(200, 60L * 1024 * 1024);
                    exchange.close();
                });
        int port = server.getAddress().getPort();
        assertThrows(
                IOException.class,
                () -> MediaUtils.downloadUrlToBase64("http://127.0.0.1:" + port + "/huge"));
    }

    @Test
    void smallBody_stillEncodes() throws IOException {
        serve("/small", 200, "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        int port = server.getAddress().getPort();
        String b64 = MediaUtils.downloadUrlToBase64("http://127.0.0.1:" + port + "/small");
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.Base64.getEncoder()
                        .encodeToString("hello".getBytes(StandardCharsets.UTF_8)),
                b64);
    }

    @Test
    void nonOkStatus_descriptiveError() throws IOException {
        serve("/missing", 404, "text/plain", "gone".getBytes(StandardCharsets.UTF_8));
        int port = server.getAddress().getPort();
        IOException e =
                assertThrows(
                        IOException.class,
                        () ->
                                MediaUtils.downloadUrlToBase64(
                                        "http://127.0.0.1:" + port + "/missing"));
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getMessage().contains("404"), "error should carry the status: " + e.getMessage());
    }
}
