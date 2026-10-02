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
package io.agentscope.builder.web.managed.service;

import io.agentscope.builder.web.managed.ResourceShareDto;
import io.agentscope.builder.web.managed.VaultCredentialDto;
import io.agentscope.builder.web.managed.VaultDto;
import io.agentscope.builder.web.persistence.jpa.VaultCredentialEntity;
import io.agentscope.builder.web.persistence.jpa.VaultCredentialEntityRepository;
import io.agentscope.builder.web.persistence.jpa.VaultEntity;
import io.agentscope.builder.web.persistence.jpa.VaultEntityRepository;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.share.ResourceAccessService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** CRUD for credential vaults with encrypted secret storage. */
@Service
@Transactional
public class VaultService {

    /** Resource-type key used for {@link ResourceAccessService} share grants. */
    public static final String RESOURCE_TYPE = "vault";

    /** Request body for creating a vault. */
    public record CreateVaultRequest(String displayName, Map<String, Object> metadata) {}

    /** Request body for adding a credential. */
    public record AddCredentialRequest(String type, String label, String target, String secret) {}

    private final VaultEntityRepository vaultRepository;
    private final VaultCredentialEntityRepository credentialRepository;
    private final VaultCrypto vaultCrypto;
    private final ManagedJsonHelper jsonHelper;
    private final ResourceAccessService resourceAccessService;

    public VaultService(
            VaultEntityRepository vaultRepository,
            VaultCredentialEntityRepository credentialRepository,
            VaultCrypto vaultCrypto,
            ManagedJsonHelper jsonHelper,
            ResourceAccessService resourceAccessService) {
        this.vaultRepository = vaultRepository;
        this.credentialRepository = credentialRepository;
        this.vaultCrypto = vaultCrypto;
        this.jsonHelper = jsonHelper;
        this.resourceAccessService = resourceAccessService;
    }

    /** Lists vaults owned by the user. */
    @Transactional(readOnly = true)
    public List<VaultDto> list(String ownerId) {
        return vaultRepository.findByOwnerIdOrderByCreatedAtAsc(ownerId).stream()
                .map(this::toVaultDto)
                .toList();
    }

    /** Returns a single vault when owned by, or shared (at least RUN) with, the caller. */
    @Transactional(readOnly = true)
    public VaultDto get(String ownerId, String vaultId) {
        return toVaultDto(requireAccess(ownerId, vaultId, Tier.RUN));
    }

    /** Creates a vault. */
    public VaultDto create(String ownerId, CreateVaultRequest request) {
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName is required");
        }
        long now = System.currentTimeMillis();
        VaultEntity entity = new VaultEntity();
        entity.setVaultId(ManagedJsonHelper.randomId("vlt_"));
        entity.setOwnerId(ownerId);
        entity.setDisplayName(request.displayName());
        entity.setMetadataJson(jsonHelper.writeJson(request.metadata()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toVaultDto(vaultRepository.save(entity));
    }

    /** Deletes a vault and all credentials. */
    public void delete(String ownerId, String vaultId) {
        requireAccess(ownerId, vaultId, Tier.EDIT);
        credentialRepository.deleteByVaultId(vaultId);
        vaultRepository.findByVaultId(vaultId).ifPresent(vaultRepository::delete);
    }

    /** Lists credential metadata for a vault (no plaintext). */
    @Transactional(readOnly = true)
    public List<VaultCredentialDto> listCredentials(String ownerId, String vaultId) {
        requireAccess(ownerId, vaultId, Tier.RUN);
        return credentialRepository.findByVaultIdOrderByCreatedAtAsc(vaultId).stream()
                .map(this::toCredentialDto)
                .toList();
    }

    /** Adds an encrypted credential to a vault. */
    public VaultCredentialDto addCredential(
            String ownerId, String vaultId, AddCredentialRequest request) {
        requireAccess(ownerId, vaultId, Tier.EDIT);
        if (request.type() == null || request.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        if (request.secret() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret is required");
        }
        long now = System.currentTimeMillis();
        VaultCredentialEntity entity = new VaultCredentialEntity();
        entity.setCredentialId(ManagedJsonHelper.randomId("cred_"));
        entity.setVaultId(vaultId);
        entity.setCredentialType(request.type());
        entity.setLabel(request.label());
        entity.setTarget(request.target());
        entity.setCiphertext(vaultCrypto.encrypt(request.secret()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        VaultCredentialDto dto = toCredentialDto(credentialRepository.save(entity));
        touchVault(vaultId, now);
        return dto;
    }

    /** Deletes a credential from a vault. */
    public void deleteCredential(String ownerId, String vaultId, String credentialId) {
        requireAccess(ownerId, vaultId, Tier.EDIT);
        VaultCredentialEntity credential =
                credentialRepository
                        .findByCredentialId(credentialId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Credential not found: " + credentialId));
        if (!vaultId.equals(credential.getVaultId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Credential not found in vault: " + credentialId);
        }
        credentialRepository.delete(credential);
        touchVault(vaultId, System.currentTimeMillis());
    }

    /** Returns decrypted secret JSON for internal MCP injection. */
    @Transactional(readOnly = true)
    public String getDecryptedSecret(String ownerId, String credentialId) {
        VaultCredentialEntity credential =
                credentialRepository
                        .findByCredentialId(credentialId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Credential not found: " + credentialId));
        requireAccess(ownerId, credential.getVaultId(), Tier.RUN);
        return vaultCrypto.decrypt(credential.getCiphertext());
    }

    /** Lists share grants on a vault. Owner/EDIT-tier only. */
    @Transactional(readOnly = true)
    public List<ResourceShareDto> listShares(String ownerId, String vaultId) {
        requireAccess(ownerId, vaultId, Tier.EDIT);
        return resourceAccessService.listShares(RESOURCE_TYPE, vaultId);
    }

    /** Shares a vault with a user or the whole workspace. Owner/EDIT-tier only. */
    public ResourceShareDto share(
            String ownerId, String vaultId, String granteeType, String granteeId, Tier tier) {
        VaultEntity vault = requireAccess(ownerId, vaultId, Tier.EDIT);
        return resourceAccessService.share(
                RESOURCE_TYPE, vaultId, vault.getOwnerId(), granteeType, granteeId, tier, ownerId);
    }

    /** Revokes a share grant on a vault. Owner/EDIT-tier only. */
    public void unshare(String ownerId, String vaultId, String shareId) {
        requireAccess(ownerId, vaultId, Tier.EDIT);
        resourceAccessService.unshare(RESOURCE_TYPE, vaultId, shareId);
    }

    /** Resolves the vault and verifies {@code callerId} holds at least {@code required}. */
    private VaultEntity requireAccess(String callerId, String vaultId, Tier required) {
        VaultEntity vault =
                vaultRepository
                        .findByVaultId(vaultId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Vault not found: " + vaultId));
        resourceAccessService.require(
                callerId, vault.getOwnerId(), RESOURCE_TYPE, vaultId, required);
        return vault;
    }

    private void touchVault(String vaultId, long updatedAt) {
        vaultRepository
                .findByVaultId(vaultId)
                .ifPresent(
                        v -> {
                            v.setUpdatedAt(updatedAt);
                            vaultRepository.save(v);
                        });
    }

    private VaultDto toVaultDto(VaultEntity entity) {
        return new VaultDto(
                entity.getVaultId(),
                entity.getOwnerId(),
                entity.getDisplayName(),
                jsonHelper.readMap(entity.getMetadataJson()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private VaultCredentialDto toCredentialDto(VaultCredentialEntity entity) {
        return new VaultCredentialDto(
                entity.getCredentialId(),
                entity.getCredentialType(),
                entity.getLabel(),
                entity.getTarget(),
                entity.getCreatedAt());
    }
}
