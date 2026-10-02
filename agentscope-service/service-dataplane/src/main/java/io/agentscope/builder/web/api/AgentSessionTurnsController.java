package io.agentscope.builder.web.api;

import io.agentscope.builder.web.managed.AgentSessionInput;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.SessionFileService;
import io.agentscope.builder.web.managed.SessionInputService;
import io.agentscope.builder.web.managed.SessionTurnInbox;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/agent-sessions/{session}/turns")
public final class AgentSessionTurnsController {
    private final SessionTurnInbox inbox;

    private final SessionInputService inputs;
    private final SessionFileService files;
    private final DataSessionService sessions;

    public AgentSessionTurnsController(
            SessionTurnInbox inbox,
            SessionInputService inputs,
            SessionFileService files,
            DataSessionService sessions) {
        this.inbox = inbox;
        this.inputs = inputs;
        this.files = files;
        this.sessions = sessions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SessionTurnInbox.Turn> create(
            @PathVariable String session,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody AgentSessionInput input,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            sessions.get((String) auth.getPrincipal(), session);
                            return inbox.accept(
                                    (String) auth.getPrincipal(),
                                    session,
                                    key,
                                    files.resolveInput(session, input.normalized()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/steer")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SessionInputService.Receipt> steer(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody AgentSessionInput input,
            Authentication auth) {
        return Mono.fromCallable(
                        () ->
                                inputs.accept(
                                        (String) auth.getPrincipal(),
                                        session,
                                        id,
                                        "steer",
                                        key,
                                        input))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<SessionTurnInbox.Turn> get(
            @PathVariable String session, @PathVariable String id, Authentication auth) {
        return Mono.fromCallable(() -> inbox.get((String) auth.getPrincipal(), session, id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/actions")
    public Mono<List<Map<String, Object>>> actions(
            @PathVariable String session, @PathVariable String id, Authentication auth) {
        return Mono.fromCallable(() -> inbox.actions((String) auth.getPrincipal(), session, id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record AnswerActions(List<SessionTurnInbox.Answer> answers) {}

    @PostMapping("/{id}/actions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SessionTurnInbox.Turn> answer(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody AnswerActions input,
            Authentication auth) {
        return Mono.fromCallable(
                        () ->
                                inbox.answer(
                                        (String) auth.getPrincipal(),
                                        session,
                                        id,
                                        key,
                                        input.answers()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<List<SessionTurnInbox.Turn>> list(
            @PathVariable String session, Authentication auth) {
        return Mono.fromCallable(() -> inbox.list((String) auth.getPrincipal(), session))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SessionTurnInbox.Turn> cancel(
            @PathVariable String session, @PathVariable String id, Authentication auth) {
        return Mono.fromCallable(() -> inbox.cancel((String) auth.getPrincipal(), session, id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SessionTurnInbox.Turn> resume(
            @PathVariable String session, @PathVariable String id, Authentication auth) {
        return Mono.fromCallable(() -> inbox.resume((String) auth.getPrincipal(), session, id))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
