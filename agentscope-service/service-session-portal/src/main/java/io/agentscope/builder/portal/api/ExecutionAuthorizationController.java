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
package io.agentscope.builder.portal.api;

import io.agentscope.builder.portal.session.PortalExecutionAuthorization;
import io.agentscope.builder.runtime.protocol.RuntimeExecutionGrant;
import io.agentscope.builder.runtime.protocol.RuntimeExecutionRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class ExecutionAuthorizationController {
    private final PortalExecutionAuthorization authorization;

    public ExecutionAuthorizationController(PortalExecutionAuthorization authorization) {
        this.authorization = authorization;
    }

    @PostMapping("/api/internal/runtime/execution-grants")
    public Mono<RuntimeExecutionGrant> authorize(@RequestBody RuntimeExecutionRequest request) {
        return Mono.fromCallable(() -> authorization.authorize(request))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
