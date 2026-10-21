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

import io.agentscope.builder.portal.session.PortalInputAdmission;
import io.agentscope.builder.portal.session.PortalTurnControl;
import io.agentscope.builder.runtime.protocol.RuntimeAgentSignal;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** The authenticated Go courier already checked the physical task lease and pending node. */
@RestController
public class PortalAgentSignalsController {
    private final PortalTurnControl controls;
    private final PortalInputAdmission commands;

    public PortalAgentSignalsController(PortalTurnControl controls, PortalInputAdmission commands) {
        this.controls = controls;
        this.commands = commands;
    }

    @PostMapping("/api/internal/runtime/agent-signals")
    public Mono<Map<String, Object>> accept(@RequestBody RuntimeAgentSignal request) {
        return Mono.fromCallable(
                        () -> {
                            var receipt = commands.view(controls.agentSignal(request));
                            receipt.put("version", 1);
                            return receipt;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
