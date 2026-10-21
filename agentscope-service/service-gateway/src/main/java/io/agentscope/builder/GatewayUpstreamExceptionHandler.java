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
package io.agentscope.builder;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.TimeoutException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

/** Preserve unknown write outcomes while making upstream transport failures actionable. */
@Component
@Order(-2)
public final class GatewayUpstreamExceptionHandler implements WebExceptionHandler {
    private static final Logger log =
            LoggerFactory.getLogger(GatewayUpstreamExceptionHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        boolean timeout = timedOut(error);
        if (route == null
                || exchange.getResponse().isCommitted()
                || (!timeout && !disconnected(error))) return Mono.error(error);
        ServerWebExchangeUtils.reset(exchange);
        var response = exchange.getResponse();
        response.setStatusCode(timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        response.getHeaders().set("X-Request-ID", exchange.getRequest().getId());
        String code = timeout ? "upstream_timeout" : "upstream_unavailable";
        byte[] body =
                ("{\"code\":\""
                                + code
                                + "\",\"message\":\"Upstream connection failed. The request may"
                                + " have completed; retry writes only with the same"
                                + " Idempotency-Key.\"}")
                        .getBytes(StandardCharsets.UTF_8);
        response.getHeaders().setContentLength(body.length);
        log.warn(
                "gateway.upstream.failed route={} request_id={} status={} cause={}",
                route.getId(),
                exchange.getRequest().getId(),
                response.getStatusCode().value(),
                error.getClass().getSimpleName());
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    static boolean disconnected(Throwable error) {
        if (timedOut(error)) return false;
        for (int depth = 0; error != null && depth < 16; depth++, error = error.getCause())
            if (error instanceof IOException || error instanceof PrematureCloseException)
                return true;
        return false;
    }

    private static boolean timedOut(Throwable error) {
        for (int depth = 0; error != null && depth < 16; depth++, error = error.getCause())
            if (error instanceof ConnectTimeoutException
                    || error instanceof TimeoutException
                    || error instanceof java.util.concurrent.TimeoutException) return true;
        return false;
    }
}
