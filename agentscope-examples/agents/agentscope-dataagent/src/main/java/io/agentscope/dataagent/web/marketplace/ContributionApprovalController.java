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
package io.agentscope.dataagent.web.marketplace;

import io.agentscope.dataagent.web.marketplace.MarketContributionController.ContributionView;
import io.agentscope.dataagent.web.persistence.jpa.ContributionEntity;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Admin REST surface for reviewing contributions and promoting them into the shared workspace.
 *
 * <ul>
 *   <li>{@code GET  /api/admin/contributions?status=PENDING} — list contributions by status
 *   <li>{@code GET  /api/admin/contributions/{id}} — fetch a single contribution including its
 *       payload (FileEntry list) and any reviewer-edited {@code approvedPayload}
 *   <li>{@code POST /api/admin/contributions/{id}/approve} — approve + materialize payload (with
 *       optional admin edits via {@code approvedPayload})
 *   <li>{@code POST /api/admin/contributions/{id}/reject} — reject with reason
 * </ul>
 *
 * <p>All endpoints require the {@code ADMIN} role; non-admin callers receive {@code 403}.
 */
@RestController
@RequestMapping("/api/admin/contributions")
public class ContributionApprovalController {

    private final MarketContributionService service;

    public ContributionApprovalController(MarketContributionService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<List<ContributionView>> list(
            @RequestParam(value = "status", required = false) String status, Authentication auth) {
        requireAdmin(auth);
        return Mono.fromCallable(
                () ->
                        service
                                .listByStatus(
                                        status == null || status.isBlank()
                                                ? ContributionEntity.STATUS_PENDING
                                                : status.toUpperCase())
                                .stream()
                                .map(ContributionView::from)
                                .toList());
    }

    @GetMapping("/{id}")
    public Mono<ContributionDetailView> get(@PathVariable("id") long id, Authentication auth) {
        requireAdmin(auth);
        return Mono.fromCallable(
                () -> {
                    try {
                        ContributionEntity e = service.get(id);
                        return new ContributionDetailView(
                                ContributionView.from(e),
                                service.readOriginalPayload(e),
                                service.readApprovedPayload(e));
                    } catch (IllegalArgumentException ex) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
                    }
                });
    }

    @PostMapping("/{id}/approve")
    public Mono<ContributionView> approve(
            @PathVariable("id") long id, @RequestBody ReviewRequest req, Authentication auth) {
        requireAdmin(auth);
        String reviewer = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    try {
                        return ContributionView.from(
                                service.approve(id, reviewer, req.note(), req.approvedPayload()));
                    } catch (IllegalArgumentException e) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
                    } catch (IllegalStateException e) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
                    }
                });
    }

    @PostMapping("/{id}/reject")
    public Mono<ContributionView> reject(
            @PathVariable("id") long id, @RequestBody ReviewRequest req, Authentication auth) {
        requireAdmin(auth);
        String reviewer = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    try {
                        return ContributionView.from(service.reject(id, reviewer, req.note()));
                    } catch (IllegalArgumentException e) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
                    } catch (IllegalStateException e) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
                    }
                });
    }

    private static void requireAdmin(Authentication auth) {
        if (auth == null
                || auth.getAuthorities() == null
                || auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .noneMatch("ROLE_ADMIN"::equals)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }

    public record ReviewRequest(String note, List<FileEntry> approvedPayload) {}

    public record ContributionDetailView(
            ContributionView contribution,
            List<FileEntry> payload,
            List<FileEntry> approvedPayload) {}
}
