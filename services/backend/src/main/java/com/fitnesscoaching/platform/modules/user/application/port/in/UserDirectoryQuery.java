package com.fitnesscoaching.platform.modules.user.application.port.in;

import com.fitnesscoaching.platform.modules.user.application.model.UserDisplaySummary;
import com.fitnesscoaching.platform.modules.user.application.model.UserDisplaySummaryPage;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Privacy-minimal identity projections for other modules. This port never exposes email,
 * phone, settings, permissions, or other account-private fields.
 */
public interface UserDirectoryQuery {
    Map<UUID, UserDisplaySummary> findDisplaySummaries(Collection<UUID> userIds);

    Optional<UserDisplaySummary> findActiveRoleMemberByEmail(String normalizedEmail, String roleCode);

    UserDisplaySummaryPage searchActiveRoleMembers(Collection<UUID> candidateIds, String roleCode,
                                                   String displayNameQuery, int page, int size);
}
