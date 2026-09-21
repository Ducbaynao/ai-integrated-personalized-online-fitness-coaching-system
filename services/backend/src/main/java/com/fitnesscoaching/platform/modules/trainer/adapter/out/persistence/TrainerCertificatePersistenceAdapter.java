package com.fitnesscoaching.platform.modules.trainer.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerCertificatePort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class TrainerCertificatePersistenceAdapter implements TrainerCertificatePort {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public TrainerCertificatePersistenceAdapter(NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    public boolean allCertificatesExistAndBelongToTrainer(List<UUID> certificateIds, UUID trainerId) {
        if (certificateIds == null || certificateIds.isEmpty()) {
            return true;
        }

        String sql = """
                SELECT count(DISTINCT id)
                FROM fitness.trainer_certificates
                WHERE id IN (:certificateIds)
                  AND trainer_id = :trainerId
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("certificateIds", certificateIds)
                .addValue("trainerId", trainerId);

        Integer count = namedParameterJdbcTemplate.queryForObject(sql, params, Integer.class);
        return count != null && count == (int) certificateIds.stream().distinct().count();
    }
}
