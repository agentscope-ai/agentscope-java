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
package io.agentscope.harness.claw.app.usage;

import io.agentscope.harness.claw.app.usage.UsageStore.AgentRollup;
import io.agentscope.harness.claw.app.usage.UsageStore.BucketCount;
import io.agentscope.harness.claw.app.usage.UsageStore.GroupCount;
import io.agentscope.harness.claw.app.usage.UsageStore.UsageSummary;
import io.agentscope.harness.claw.app.usage.UsageStore.UserRollup;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for usage metrics.
 *
 * <p>User endpoints (any authenticated user, scoped to caller):
 *
 * <ul>
 *   <li>{@code GET /api/usage/me/summary} — caller's aggregate totals
 *   <li>{@code GET /api/usage/me/daily?days=7} — caller's daily turn counts
 * </ul>
 *
 * <p>Admin endpoints (require {@code ROLE_ADMIN}):
 *
 * <ul>
 *   <li>{@code GET /api/admin/usage/summary} — platform-wide totals
 *   <li>{@code GET /api/admin/usage/hourly?hours=24} — hourly turn counts
 *   <li>{@code GET /api/admin/usage/daily?days=30} — daily turn counts
 *   <li>{@code GET /api/admin/usage/top-users?days=7&n=10} — top users by turn count
 *   <li>{@code GET /api/admin/usage/top-agents?days=7&n=10} — top agents by turn count
 *   <li>{@code GET /api/admin/usage/users-rollup?days=30} — per-user totals + last-seen
 *   <li>{@code GET /api/admin/usage/agents-rollup?days=30} — per-agent totals + last-used
 *   <li>{@code GET /api/admin/usage/user/{userId}/daily?days=30} — daily turns for one user
 * </ul>
 */
@RestController
public class UsageController {

    private final UsageStore usageStore;

    public UsageController(UsageStore usageStore) {
        this.usageStore = usageStore;
    }

    // -----------------------------------------------------------------
    //  User-scoped endpoints  (/api/usage/me/**)
    // -----------------------------------------------------------------

    @GetMapping("/api/usage/me/summary")
    public Mono<UsageSummary> mySummary(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> usageStore.summaryForUser(userId));
    }

    @GetMapping("/api/usage/me/daily")
    public Mono<List<BucketCount>> myDaily(
            @RequestParam(name = "days", defaultValue = "7") int days, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> usageStore.dailyTurnsForUser(userId, days));
    }

    // -----------------------------------------------------------------
    //  Admin-scoped endpoints  (/api/admin/usage/**)
    // -----------------------------------------------------------------

    @GetMapping("/api/admin/usage/summary")
    public Mono<UsageSummary> summary() {
        return Mono.fromCallable(usageStore::summary);
    }

    @GetMapping("/api/admin/usage/hourly")
    public Mono<List<BucketCount>> hourly(
            @RequestParam(name = "hours", defaultValue = "24") int hours) {
        return Mono.fromCallable(() -> usageStore.hourlyTurns(hours));
    }

    @GetMapping("/api/admin/usage/daily")
    public Mono<List<BucketCount>> daily(
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return Mono.fromCallable(() -> usageStore.dailyTurns(days));
    }

    @GetMapping("/api/admin/usage/top-users")
    public Mono<List<GroupCount>> topUsers(
            @RequestParam(name = "days", defaultValue = "7") int days,
            @RequestParam(name = "n", defaultValue = "10") int n) {
        return Mono.fromCallable(() -> usageStore.topUsersByTurns(days, n));
    }

    @GetMapping("/api/admin/usage/top-agents")
    public Mono<List<GroupCount>> topAgents(
            @RequestParam(name = "days", defaultValue = "7") int days,
            @RequestParam(name = "n", defaultValue = "10") int n) {
        return Mono.fromCallable(() -> usageStore.topAgentsByTurns(days, n));
    }

    /** Full per-user rollup over the past N days. Powers the admin "Users" page. */
    @GetMapping("/api/admin/usage/users-rollup")
    public Mono<List<UserRollup>> usersRollup(
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return Mono.fromCallable(() -> usageStore.usersRollup(days));
    }

    /** Full per-agent rollup over the past N days. Powers the admin "Agents" page. */
    @GetMapping("/api/admin/usage/agents-rollup")
    public Mono<List<AgentRollup>> agentsRollup(
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return Mono.fromCallable(() -> usageStore.agentsRollup(days));
    }

    /** Daily turn counts for one user — admin drill-down. */
    @GetMapping("/api/admin/usage/user/{userId}/daily")
    public Mono<List<BucketCount>> userDaily(
            @org.springframework.web.bind.annotation.PathVariable("userId") String userId,
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return Mono.fromCallable(() -> usageStore.dailyTurnsForUser(userId, days));
    }
}
