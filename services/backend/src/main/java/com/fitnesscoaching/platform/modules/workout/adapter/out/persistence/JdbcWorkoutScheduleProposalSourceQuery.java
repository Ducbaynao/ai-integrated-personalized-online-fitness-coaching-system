package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleProposalSourceQuery;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Repository
public class JdbcWorkoutScheduleProposalSourceQuery implements WorkoutScheduleProposalSourceQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcWorkoutScheduleProposalSourceQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Source> findByVersionIds(Collection<UUID> versionIds) {
        var ids = versionIds == null ? java.util.List.<UUID>of()
                : versionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<UUID, MutableSource> found = new LinkedHashMap<>();
        jdbc.query("""
                SELECT v.id AS version_id, p.id AS plan_id, p.name AS plan_name,
                       s.id AS session_id, s.name AS session_name, s.session_focus
                FROM fitness.workout_plan_versions v
                JOIN fitness.workout_plans p ON p.id = v.workout_plan_id
                LEFT JOIN fitness.workout_plan_sessions s ON s.workout_plan_version_id = v.id
                WHERE v.id IN (:ids)
                """, new MapSqlParameterSource("ids", ids), rs -> {
            UUID versionId = rs.getObject("version_id", UUID.class);
            MutableSource source = found.get(versionId);
            if (source == null) {
                source = new MutableSource(rs.getObject("plan_id", UUID.class), rs.getString("plan_name"));
                found.put(versionId, source);
            }
            UUID sessionId = rs.getObject("session_id", UUID.class);
            if (sessionId != null) source.sessions.put(sessionId,
                    new Session(sessionId, rs.getString("session_name"), rs.getString("session_focus")));
        });
        Map<UUID, Source> result = new LinkedHashMap<>();
        found.forEach((id, source) -> result.put(id,
                new Source(source.planId, id, source.planName, Map.copyOf(source.sessions))));
        return Map.copyOf(result);
    }

    private static final class MutableSource {
        private final UUID planId;
        private final String planName;
        private final Map<UUID, Session> sessions = new LinkedHashMap<>();

        private MutableSource(UUID planId, String planName) {
            this.planId = planId;
            this.planName = planName;
        }
    }
}
