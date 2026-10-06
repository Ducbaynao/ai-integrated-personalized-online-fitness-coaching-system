package com.fitnesscoaching.platform.modules.user.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.user.application.model.UserDisplaySummary;
import com.fitnesscoaching.platform.modules.user.application.model.UserDisplaySummaryPage;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserDirectoryQuery;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class UserDirectoryReadAdapter implements UserDirectoryQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public UserDirectoryReadAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, UserDisplaySummary> findDisplaySummaries(Collection<UUID> userIds) {
        List<UUID> ids = distinctIds(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<UserDisplaySummary> rows = jdbc.query("""
                SELECT id, display_name
                FROM fitness.users
                WHERE id IN (:ids)
                """, new MapSqlParameterSource("ids", ids), (rs, ignored) ->
                new UserDisplaySummary(rs.getObject("id", UUID.class), rs.getString("display_name")));
        Map<UUID, UserDisplaySummary> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(row.userId(), row));
        return Map.copyOf(result);
    }

    @Override
    public Optional<UserDisplaySummary> findActiveRoleMemberByEmail(String normalizedEmail, String roleCode) {
        List<UserDisplaySummary> rows = jdbc.query("""
                SELECT u.id, u.display_name
                FROM fitness.users u
                JOIN fitness.user_roles ur ON ur.user_id = u.id AND ur.revoked_at IS NULL
                JOIN fitness.roles r ON r.id = ur.role_id AND r.code = :roleCode
                WHERE u.status = 'ACTIVE'::fitness.account_status
                  AND u.email = :email
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("roleCode", roleCode)
                .addValue("email", normalizedEmail), (rs, ignored) ->
                new UserDisplaySummary(rs.getObject("id", UUID.class), rs.getString("display_name")));
        return rows.stream().findFirst();
    }

    @Override
    public UserDisplaySummaryPage searchActiveRoleMembers(Collection<UUID> candidateIds, String roleCode,
                                                          String displayNameQuery, int page, int size) {
        List<UUID> ids = distinctIds(candidateIds);
        if (ids.isEmpty()) {
            return new UserDisplaySummaryPage(List.of(), page, size);
        }
        String query = displayNameQuery == null ? "" : displayNameQuery.trim().toLowerCase(Locale.ROOT);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("ids", ids)
                .addValue("roleCode", roleCode)
                .addValue("pattern", "%" + escapeLike(query) + "%")
                .addValue("limit", size)
                .addValue("offset", page * size);
        List<UserDisplaySummary> rows = jdbc.query("""
                SELECT u.id, u.display_name
                FROM fitness.users u
                JOIN fitness.user_roles ur ON ur.user_id = u.id AND ur.revoked_at IS NULL
                JOIN fitness.roles r ON r.id = ur.role_id AND r.code = :roleCode
                WHERE u.id IN (:ids)
                  AND u.status = 'ACTIVE'::fitness.account_status
                  AND lower(u.display_name) LIKE :pattern ESCAPE '\\'
                ORDER BY lower(u.display_name), u.id
                LIMIT :limit OFFSET :offset
                """, parameters, (rs, ignored) ->
                new UserDisplaySummary(rs.getObject("id", UUID.class), rs.getString("display_name")));
        return new UserDisplaySummaryPage(rows, page, size);
    }

    private static List<UUID> distinctIds(Collection<UUID> ids) {
        return ids == null ? List.of() : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
