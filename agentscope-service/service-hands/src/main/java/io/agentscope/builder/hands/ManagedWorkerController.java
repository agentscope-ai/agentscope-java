/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.hands;

import io.agentscope.builder.runtime.managed.ManagedWorkerProtocol;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/v1/handss/environments/{environment}/tool-tasks")
public final class ManagedWorkerController {
    private final ManagedWorkerService workers;

    public ManagedWorkerController(ManagedWorkerService workers) {
        this.workers = workers;
    }

    @PostMapping("/claim")
    public Mono<Object> claim(
            @PathVariable String environment,
            @RequestHeader(value = ManagedWorkerService.ENVIRONMENT_KEY, required = false)
                    String key,
            @RequestBody ManagedWorkerProtocol.Claim request) {
        return Mono.fromCallable(() -> (Object) workers.claim(environment, key, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/renew")
    public Mono<Object> renew(
            @PathVariable String environment,
            @RequestHeader(value = ManagedWorkerService.ENVIRONMENT_KEY, required = false)
                    String key,
            @RequestBody ManagedWorkerProtocol.Lease request) {
        return Mono.fromCallable(() -> (Object) workers.renew(environment, key, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/started")
    public Mono<Object> started(
            @PathVariable String environment,
            @RequestHeader(value = ManagedWorkerService.ENVIRONMENT_KEY, required = false)
                    String key,
            @RequestBody ManagedWorkerProtocol.Lease request) {
        return Mono.fromCallable(() -> (Object) workers.started(environment, key, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/unknown")
    public Mono<Object> unknown(
            @PathVariable String environment,
            @RequestHeader(value = ManagedWorkerService.ENVIRONMENT_KEY, required = false)
                    String key,
            @RequestBody ManagedWorkerProtocol.Lease request) {
        return Mono.fromCallable(() -> (Object) workers.unknown(environment, key, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/result")
    public Mono<Object> result(
            @PathVariable String environment,
            @RequestHeader(value = ManagedWorkerService.ENVIRONMENT_KEY, required = false)
                    String key,
            @RequestBody ManagedWorkerProtocol.Result request) {
        return Mono.fromCallable(() -> (Object) workers.result(environment, key, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/output")
    public Mono<Object> output(
            @PathVariable String environment,
            @RequestHeader(value = ManagedWorkerService.ENVIRONMENT_KEY, required = false)
                    String key,
            @RequestBody ManagedWorkerProtocol.Output request) {
        return Mono.fromCallable(() -> workers.output(environment, key, request))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
