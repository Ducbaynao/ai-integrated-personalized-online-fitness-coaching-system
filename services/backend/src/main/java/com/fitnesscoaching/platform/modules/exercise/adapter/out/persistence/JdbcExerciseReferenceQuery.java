package com.fitnesscoaching.platform.modules.exercise.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcExerciseReferenceQuery implements ExerciseReferenceQuery {
    private final JdbcTemplate jdbc;
    public JdbcExerciseReferenceQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<AuthoringReference> lockAuthoringReferences(Collection<UUID> variationIds) {
        List<UUID> ordered = variationIds == null ? List.of() : variationIds.stream().distinct()
                .sorted(Comparator.comparing(UUID::toString)).toList();
        if (ordered.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
        return jdbc.query("""
                SELECT v.id variation_id, e.id exercise_id
                FROM fitness.exercise_variations v
                JOIN fitness.exercises e ON e.id = v.exercise_id
                WHERE v.id IN (%s) AND v.is_active AND e.admin_status = 'ACTIVE' AND e.deleted_at IS NULL
                ORDER BY v.id FOR SHARE OF v, e
                """.formatted(placeholders), (rs, row) -> new AuthoringReference(
                rs.getObject("variation_id", UUID.class), rs.getObject("exercise_id", UUID.class)), ordered.toArray());
    }

    @Override
    public Optional<HistoricalReference> resolveHistorical(UUID variationId) {
        return jdbc.query("""
                SELECT v.id variation_id, e.id exercise_id, e.name exercise_name, v.name variation_name,
                       e.admin_status, e.deleted_at, v.is_active,
                       m.canonical_exercise_id, ce.name canonical_name
                FROM fitness.exercise_variations v
                JOIN fitness.exercises e ON e.id = v.exercise_id
                LEFT JOIN fitness.exercise_canonical_mappings m ON m.duplicate_exercise_id = e.id
                LEFT JOIN fitness.exercises ce ON ce.id = m.canonical_exercise_id
                WHERE v.id = ?
                """, (rs, row) -> {
            String status = rs.getString("admin_status");
            PresentationState state = rs.getTimestamp("deleted_at") != null || !rs.getBoolean("is_active")
                    || (!"ACTIVE".equals(status) && !"ARCHIVED".equals(status))
                    ? PresentationState.UNAVAILABLE : PresentationState.valueOf(status);
            return new HistoricalReference(rs.getObject("variation_id", UUID.class),
                    rs.getObject("exercise_id", UUID.class), rs.getString("exercise_name"),
                    rs.getString("variation_name"), state, rs.getObject("canonical_exercise_id", UUID.class),
                    rs.getString("canonical_name"));
        }, variationId).stream().findFirst();
    }
}
