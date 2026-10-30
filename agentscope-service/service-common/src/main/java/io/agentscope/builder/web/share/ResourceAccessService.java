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
package io.agentscope.builder.web.share;

import io.agentscope.builder.web.managed.ResourceShareDto;
import io.agentscope.builder.web.managed.service.ManagedJsonHelper;
import io.agentscope.builder.web.persistence.jpa.ResourceShareEntity;
import io.agentscope.builder.web.persistence.jpa.ResourceShareEntityRepository;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generic owner-or-shared access control for non-agent managed resources (environments, memory
 * stores, vaults). Reuses {@link AgentAclService.Tier} so all ACL decisions in the app share the
 * same three-tier vocabulary ({@code CLONE < RUN < EDIT}), but keeps grants in a single
 * resource-type-keyed table ({@link ResourceShareEntity}) instead of one table per resource kind.
 *
 * <p>Rule: the resource owner always holds {@code EDIT}; any grant matching the caller (direct
 * {@code USER} grant or a {@code WORKSPACE} grant applying to every logged-in user) contributes
 * its tier, and the highest applicable tier wins.
 */
@Service
public class ResourceAccessService {

    private final ResourceShareEntityRepository repository;

    public ResourceAccessService(ResourceShareEntityRepository repository) {
        this.repository = repository;
    }

    /** Returns the highest tier {@code userId} holds on the resource, or {@code null} if none. */
    public Tier tierFor(String userId, String ownerId, String resourceType, String resourceId) {
        if (userId != null && userId.equals(ownerId)) {
            return Tier.EDIT;
        }
        return highestMatchingGrant(
                userId, repository.findByResourceTypeAndResourceId(resourceType, resourceId));
    }

    /** {@code true} iff {@code userId} holds at least {@code required} on the resource. */
    public boolean can(
            String userId, String ownerId, String resourceType, String resourceId, Tier required) {
        Tier held = tierFor(userId, ownerId, resourceType, resourceId);
        return held != null && held.implies(required);
    }

    /**
     * Verifies {@code userId} holds at least {@code required} on the resource.
     *
     * @throws ResponseStatusException 403 if the tier is insufficient
     */
    public void require(
            String userId, String ownerId, String resourceType, String resourceId, Tier required) {
        if (!can(userId, ownerId, resourceType, resourceId, required)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Required tier " + required.name() + " on " + resourceType + " " + resourceId);
        }
    }

    /** Lists share grants on a resource. */
    public List<ResourceShareDto> listShares(String resourceType, String resourceId) {
        return repository.findByResourceTypeAndResourceId(resourceType, resourceId).stream()
                .map(ResourceAccessService::toDto)
                .toList();
    }

    /**
     * Creates or updates a grant on {@code (resourceType, resourceId)} for the given grantee.
     * Re-sharing to the same grantee updates the tier in place rather than duplicating rows.
     */
    public ResourceShareDto share(
            String resourceType,
            String resourceId,
            String ownerId,
            String granteeType,
            String granteeId,
            Tier tier,
            String createdBy) {
        validateGrantee(granteeType, granteeId);
        ResourceShareEntity entity =
                repository
                        .findByResourceTypeAndResourceIdAndGranteeTypeAndGranteeId(
                                resourceType, resourceId, granteeType, granteeId)
                        .orElseGet(
                                () -> {
                                    ResourceShareEntity fresh = new ResourceShareEntity();
                                    fresh.setShareId(ManagedJsonHelper.randomId("shr_"));
                                    fresh.setResourceType(resourceType);
                                    fresh.setResourceId(resourceId);
                                    fresh.setOwnerId(ownerId);
                                    fresh.setGranteeType(granteeType);
                                    fresh.setGranteeId(granteeId);
                                    fresh.setCreatedAt(System.currentTimeMillis());
                                    fresh.setCreatedBy(createdBy);
                                    return fresh;
                                });
        entity.setTier(tier.name());
        return toDto(repository.save(entity));
    }

    /** Removes a share grant. No-op if it does not exist. */
    public void unshare(String resourceType, String resourceId, String shareId) {
        repository
                .findByShareId(shareId)
                .filter(
                        e ->
                                resourceType.equals(e.getResourceType())
                                        && resourceId.equals(e.getResourceId()))
                .ifPresent(e -> repository.deleteByShareId(shareId));
    }

    private static void validateGrantee(String granteeType, String granteeId) {
        if (!AgentShareGrant.GRANTEE_USER.equals(granteeType)
                && !AgentShareGrant.GRANTEE_WORKSPACE.equals(granteeType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "granteeType must be USER or WORKSPACE");
        }
        if (granteeId == null || granteeId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "granteeId is required");
        }
    }

    private static Tier highestMatchingGrant(String userId, List<ResourceShareEntity> grants) {
        if (grants == null || grants.isEmpty()) {
            return null;
        }
        Tier best = null;
        for (ResourceShareEntity g : grants) {
            if (!applies(userId, g)) {
                continue;
            }
            Tier t = parseTier(g.getTier());
            if (t == null) {
                continue;
            }
            if (best == null || t.implies(best)) {
                best = t;
            }
        }
        return best;
    }

    private static boolean applies(String userId, ResourceShareEntity g) {
        if (g.getGranteeType() == null) {
            return false;
        }
        if (AgentShareGrant.GRANTEE_WORKSPACE.equals(g.getGranteeType())) {
            return userId != null;
        }
        if (AgentShareGrant.GRANTEE_USER.equals(g.getGranteeType())) {
            return userId != null && userId.equals(g.getGranteeId());
        }
        return false;
    }

    private static Tier parseTier(String raw) {
        if (raw == null) return null;
        try {
            return Tier.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static ResourceShareDto toDto(ResourceShareEntity e) {
        return new ResourceShareDto(
                e.getShareId(),
                e.getResourceType(),
                e.getResourceId(),
                e.getGranteeType(),
                e.getGranteeId(),
                e.getTier(),
                e.getCreatedAt(),
                e.getCreatedBy());
    }
}
