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
package io.agentscope.builder.web.api;

import io.agentscope.builder.web.managed.ResourceShareDto;
import io.agentscope.builder.web.managed.VaultCredentialDto;
import io.agentscope.builder.web.managed.VaultDto;
import io.agentscope.builder.web.managed.VaultService;
import io.agentscope.builder.web.managed.VaultService.AddCredentialRequest;
import io.agentscope.builder.web.managed.VaultService.CreateVaultRequest;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.share.ShareRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** REST controller for credential vaults. */
@RestController
@RequestMapping("/api/vaults")
public class VaultController {

    private final VaultService vaultService;

    public VaultController(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    /** Lists vaults owned by the authenticated user. */
    @GetMapping
    public Mono<List<VaultDto>> list(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> vaultService.list(userId));
    }

    /** Returns a single vault. */
    @GetMapping("/{id}")
    public Mono<VaultDto> get(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> vaultService.get(userId, id));
    }

    /** Creates a vault. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VaultDto> create(@RequestBody CreateVaultRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> vaultService.create(userId, req));
    }

    /** Deletes a vault. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> vaultService.delete(userId, id));
    }

    /** Lists credential metadata for a vault. */
    @GetMapping("/{id}/credentials")
    public Mono<List<VaultCredentialDto>> listCredentials(
            @PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> vaultService.listCredentials(userId, id));
    }

    /** Adds an encrypted credential to a vault. */
    @PostMapping("/{id}/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VaultCredentialDto> addCredential(
            @PathVariable("id") String id,
            @RequestBody AddCredentialRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> vaultService.addCredential(userId, id, req));
    }

    /** Deletes a credential from a vault. */
    @DeleteMapping("/{id}/credentials/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteCredential(
            @PathVariable("id") String id, @PathVariable String credentialId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> vaultService.deleteCredential(userId, id, credentialId));
    }

    /** Lists share grants on a vault. Owner-only. */
    @GetMapping("/{id}/shares")
    public Mono<List<ResourceShareDto>> listShares(
            @PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> vaultService.listShares(userId, id));
    }

    /** Shares a vault with a user or the whole workspace. Owner-only. */
    @PostMapping("/{id}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ResourceShareDto> share(
            @PathVariable("id") String id, @RequestBody ShareRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () ->
                        vaultService.share(
                                userId,
                                id,
                                req.granteeType(),
                                req.granteeId(),
                                Tier.valueOf(req.tier())));
    }

    /** Revokes a share grant on a vault. Owner-only. */
    @DeleteMapping("/{id}/shares/{shareId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unshare(
            @PathVariable("id") String id,
            @PathVariable("shareId") String shareId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> vaultService.unshare(userId, id, shareId));
    }
}
