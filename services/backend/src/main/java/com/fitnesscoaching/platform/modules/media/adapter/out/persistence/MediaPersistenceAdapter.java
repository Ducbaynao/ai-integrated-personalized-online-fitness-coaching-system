package com.fitnesscoaching.platform.modules.media.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.media.application.port.in.MediaQueryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class MediaPersistenceAdapter implements MediaQueryPort {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public MediaPersistenceAdapter(NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    public boolean allMediaExistAndOwnedBy(List<UUID> mediaIds, UUID ownerUserId) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return true;
        }

        String sql = """
                SELECT count(DISTINCT id)
                FROM fitness.media_files
                WHERE id IN (:mediaIds)
                  AND owner_user_id = :ownerUserId
                  AND deleted_at IS NULL
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("mediaIds", mediaIds)
                .addValue("ownerUserId", ownerUserId);

        Integer count = namedParameterJdbcTemplate.queryForObject(sql, params, Integer.class);
        return count != null && count == (int) mediaIds.stream().distinct().count();
    }
}
