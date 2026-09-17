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
package io.agentscope.builder.web.config;

import io.agentscope.extensions.channel.weixin.WeixinLoginChallenge;
import io.agentscope.extensions.channel.weixin.WeixinLoginClient;
import io.agentscope.extensions.channel.weixin.WeixinLoginSession;
import io.agentscope.extensions.channel.weixin.WeixinLoginStep;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Stateless internal adapter. Blocking provider operations run off the WebFlux event loop. */
@RestController
@RequestMapping("/api/internal/channel-providers/weixin/login")
final class WeixinLoginController {
    private final WeixinLoginClient client;

    WeixinLoginController(
            @Value("${builder.weixin.ilink-base-url:https://ilinkai.weixin.qq.com}") String baseUrl,
            @Value("${builder.weixin.login-timeout-ms:45000}") long timeoutMs) {
        client =
                new WeixinLoginClient(
                        baseUrl, Duration.ofMillis(Math.min(45000, Math.max(1000, timeoutMs))));
    }

    @PostMapping("/start")
    Mono<ResponseEntity<Map<String, Object>>> start(
            @RequestBody(required = false) StartRequest request) {
        return operation(
                () -> {
                    WeixinLoginChallenge challenge =
                            client.start(request == null ? null : request.botType());
                    return Map.of(
                            "qrcodeImage",
                            challenge.imageContent(),
                            "session",
                            challenge.session());
                });
    }

    @PostMapping("/poll")
    Mono<ResponseEntity<Map<String, Object>>> poll(@RequestBody SessionRequest request) {
        return operation(
                () -> stepJson(client.poll(session(request.qrcode(), request.pollingBaseUrl()))));
    }

    @PostMapping("/verify")
    Mono<ResponseEntity<Map<String, Object>>> verify(@RequestBody VerifyRequest request) {
        return operation(
                () ->
                        stepJson(
                                client.verify(
                                        session(request.qrcode(), request.pollingBaseUrl()),
                                        request.verifyCode())));
    }

    private static WeixinLoginSession session(String qrcode, String baseUrl) {
        if (qrcode == null || baseUrl == null || qrcode.length() > 4096 || baseUrl.length() > 512) {
            throw new IllegalArgumentException("Invalid login session");
        }
        return new WeixinLoginSession(qrcode, baseUrl);
    }

    private static Mono<ResponseEntity<Map<String, Object>>> operation(
            Callable<Map<String, Object>> call) {
        return Mono.fromCallable(call)
                .subscribeOn(Schedulers.boundedElastic())
                .map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body))
                .onErrorResume(
                        error ->
                                Mono.just(
                                        ResponseEntity.status(
                                                        error instanceof IllegalArgumentException
                                                                ? 400
                                                                : 502)
                                                .cacheControl(CacheControl.noStore())
                                                .body(
                                                        Map.of(
                                                                "errorCode",
                                                                "weixin_login_operation_failed"))));
    }

    private static Map<String, Object> stepJson(WeixinLoginStep step) {
        var out = new LinkedHashMap<String, Object>();
        out.put("status", step.status());
        out.put("session", step.session());
        if (step.accountId() != null) out.put("accountId", step.accountId());
        if (step.userId() != null) out.put("userId", step.userId());
        if (step.baseUrl() != null) out.put("baseUrl", step.baseUrl());
        if (step.credentials() != null) out.put("botToken", step.credentials().botToken());
        return out;
    }

    record StartRequest(String botType) {}

    record SessionRequest(String qrcode, String pollingBaseUrl) {
        @Override
        public String toString() {
            return "WeixinSessionRequest[redacted]";
        }
    }

    record VerifyRequest(String qrcode, String pollingBaseUrl, String verifyCode) {
        @Override
        public String toString() {
            return "WeixinVerifyRequest[redacted]";
        }
    }
}
