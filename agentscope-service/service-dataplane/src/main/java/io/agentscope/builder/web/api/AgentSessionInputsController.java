package io.agentscope.builder.web.api;

import io.agentscope.builder.web.managed.AgentSessionInput;
import io.agentscope.builder.web.managed.SessionInputService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions/{session}/inputs")
public final class AgentSessionInputsController {
    private final SessionInputService inputs;

    public AgentSessionInputsController(SessionInputService inputs) {
        this.inputs = inputs;
    }

    @PostMapping("/inject")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SessionInputService.Receipt> inject(
            @PathVariable String session,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody AgentSessionInput input,
            Authentication auth) {
        return Mono.fromCallable(
                        () ->
                                inputs.accept(
                                        (String) auth.getPrincipal(),
                                        session,
                                        null,
                                        "inject",
                                        key,
                                        input))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
