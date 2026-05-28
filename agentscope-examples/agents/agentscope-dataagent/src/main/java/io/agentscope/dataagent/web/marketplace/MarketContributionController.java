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

import io.agentscope.dataagent.web.persistence.jpa.ContributionEntity;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * User-facing REST surface for nominating a workspace artifact for promotion to the shared
 * workspace via admin approval.
 *
 * <ul>
 *   <li>{@code POST /api/me/contributions} — submit a new nomination
 *   <li>{@code GET  /api/me/contributions} — list the current user's submissions
 * </ul>
 */
@RestController
@RequestMapping("/api/me/contributions")
public class MarketContributionController {

    private final MarketContributionService service;

    public MarketContributionController(MarketContributionService service) {
        this.service = service;
    }

    @PostMapping
    public Mono<ContributionView> submit(@RequestBody SubmitRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    try {
                        ContributionEntity saved =
                                service.submit(
                                        userId,
                                        req.sourceAgentId(),
                                        req.targetType(),
                                        req.targetPath(),
                                        req.rationale(),
                                        req.payload());
                        return ContributionView.from(saved);
                    } catch (IllegalArgumentException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
                    }
                });
    }

    @GetMapping
    public Mono<List<ContributionView>> listMine(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> service.listMine(userId).stream().map(ContributionView::from).toList());
    }

    public record SubmitRequest(
            String sourceAgentId,
            String targetType,
            String targetPath,
            String rationale,
            String payload) {}

    public record ContributionView(
            long id,
            String status,
            String sourceUserId,
            String sourceAgentId,
            String targetType,
            String targetPath,
            String rationale,
            String reviewerUserId,
            String reviewerNote,
            long createdAt,
            long updatedAt) {
        public static ContributionView from(ContributionEntity e) {
            return new ContributionView(
                    e.getId(),
                    e.getStatus(),
                    e.getSourceUserId(),
                    e.getSourceAgentId(),
                    e.getTargetType(),
                    e.getTargetPath(),
                    e.getRationale(),
                    e.getReviewerUserId(),
                    e.getReviewerNote(),
                    e.getCreatedAt(),
                    e.getUpdatedAt());
        }
    }
}
