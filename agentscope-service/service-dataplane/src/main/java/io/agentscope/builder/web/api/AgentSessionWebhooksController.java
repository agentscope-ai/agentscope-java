package io.agentscope.builder.web.api;

import io.agentscope.builder.web.managed.SessionWebhookService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions/{session}/webhooks")
public final class AgentSessionWebhooksController {
    private final SessionWebhookService webhooks;

    public AgentSessionWebhooksController(SessionWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> register(
            @PathVariable String session,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody SessionWebhookService.Registration input,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> webhooks.register((String) auth.getPrincipal(), session, key, input))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<Map<String, Object>> list(
            @PathVariable String session,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> webhooks.list((String) auth.getPrincipal(), session, offset, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(
            @PathVariable String session, @PathVariable String id, Authentication auth) {
        return Mono.fromCallable(
                        () -> webhooks.update((String) auth.getPrincipal(), session, id, true))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/retry")
    public Mono<Map<String, Object>> retry(
            @PathVariable String session, @PathVariable String id, Authentication auth) {
        return Mono.fromCallable(
                        () -> webhooks.update((String) auth.getPrincipal(), session, id, false))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/deliveries")
    public Mono<Map<String, Object>> deliveries(
            @PathVariable String session,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            Authentication auth) {
        return Mono.fromCallable(
                        () ->
                                webhooks.deliveries(
                                        (String) auth.getPrincipal(), session, id, offset, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
