package com.fitnesscoaching.platform.modules.exercise.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.exercise.application.model.AdminExercisePage;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.AdminExerciseQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseDraftData;
import com.fitnesscoaching.platform.modules.exercise.application.port.out.AdminExerciseRepository;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExercise;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseEquipment;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseMuscle;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseSummary;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseVariation;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseLifecycleStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcAdminExerciseRepository implements AdminExerciseRepository {

    private final JdbcTemplate jdbcTemplate;
    public JdbcAdminExerciseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AdminExercisePage search(AdminExerciseQuery query) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE e.deleted_at IS NULL");
        if (query.status() != null) {
            where.append(" AND e.admin_status = ?");
            parameters.add(query.status().name());
        }
        if (query.query() != null) {
            where.append(" AND (lower(e.name) LIKE ? ESCAPE '!' OR lower(e.code) LIKE ? ESCAPE '!')");
            String pattern = "%" + escapeLike(query.query().toLowerCase(Locale.ROOT)) + "%";
            parameters.add(pattern);
            parameters.add(pattern);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.exercises e" + where,
                Long.class,
                parameters.toArray()
        );

        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(query.size());
        pageParameters.add(Math.multiplyExact(query.page(), query.size()));
        List<AdminExerciseSummary> items = jdbcTemplate.query(
                """
                SELECT e.id, e.code, e.name, c.code AS category_code, e.difficulty,
                       e.movement_pattern, e.admin_status, e.version,
                       mapping.canonical_exercise_id,
                       count(v.id) AS variation_count, e.updated_at
                FROM fitness.exercises e
                LEFT JOIN fitness.exercise_categories c ON c.id = e.category_id
                LEFT JOIN fitness.exercise_canonical_mappings mapping ON mapping.duplicate_exercise_id = e.id
                LEFT JOIN fitness.exercise_variations v ON v.exercise_id = e.id
                """ + where + "\n" + """
                GROUP BY e.id, c.code, mapping.canonical_exercise_id
                ORDER BY lower(e.name) ASC, e.id ASC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> new AdminExerciseSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("category_code"),
                        rs.getString("difficulty"),
                        rs.getString("movement_pattern"),
                        ExerciseLifecycleStatus.valueOf(rs.getString("admin_status")),
                        rs.getLong("version"),
                        rs.getObject("canonical_exercise_id", UUID.class),
                        rs.getInt("variation_count"),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                pageParameters.toArray()
        );
        long count = total == null ? 0 : total;
        int totalPages = count == 0 ? 0 : (int) ((count + query.size() - 1) / query.size());
        return new AdminExercisePage(items, query.page(), query.size(), count, totalPages);
    }

    @Override
    public Optional<AdminExercise> findById(UUID exerciseId) {
        return findExercise(exerciseId, false);
    }

    @Override
    public Optional<AdminExercise> findByIdForUpdate(UUID exerciseId) {
        return findExercise(exerciseId, true);
    }

    private Optional<AdminExercise> findExercise(UUID exerciseId, boolean lock) {
        List<BaseExercise> rows = jdbcTemplate.query(
                """
                SELECT e.id, e.code, e.name, c.code AS category_code, e.description, e.instructions,
                       e.difficulty, e.movement_pattern, e.unilateral, e.admin_status, e.version,
                       mapping.canonical_exercise_id, e.created_at, e.updated_at
                FROM fitness.exercises e
                LEFT JOIN fitness.exercise_categories c ON c.id = e.category_id
                LEFT JOIN fitness.exercise_canonical_mappings mapping ON mapping.duplicate_exercise_id = e.id
                WHERE e.id = ? AND e.deleted_at IS NULL
                """ + (lock ? " FOR UPDATE OF e" : ""),
                (rs, rowNum) -> mapBase(rs),
                exerciseId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BaseExercise base = rows.getFirst();
        List<String> tags = jdbcTemplate.query(
                """
                SELECT t.code
                FROM fitness.exercise_tag_assignments eta
                JOIN fitness.exercise_tags t ON t.id = eta.exercise_tag_id
                WHERE eta.exercise_id = ?
                ORDER BY t.code
                """,
                (rs, rowNum) -> rs.getString("code"),
                exerciseId
        );
        return Optional.of(base.toDomain(tags, loadVariations(exerciseId)));
    }

    private List<AdminExerciseVariation> loadVariations(UUID exerciseId) {
        String sql = """
                SELECT v.id, v.code, v.name, v.description, v.instructions, v.difficulty,
                       v.is_default, v.is_active,
                       mg.code AS muscle_code, em.involvement,
                       eq.code AS equipment_code, ee.requirement
                FROM fitness.exercise_variations v
                LEFT JOIN fitness.exercise_muscles em ON em.exercise_variation_id = v.id
                LEFT JOIN fitness.muscle_groups mg ON mg.id = em.muscle_group_id
                LEFT JOIN fitness.exercise_equipment ee ON ee.exercise_variation_id = v.id
                LEFT JOIN fitness.equipment eq ON eq.id = ee.equipment_id
                WHERE v.exercise_id = ?
                ORDER BY v.code, mg.code, eq.code
                """;
        Map<UUID, VariationBuilder> mapped = jdbcTemplate.query(sql, rs -> {
            Map<UUID, VariationBuilder> builders = new LinkedHashMap<>();
            while (rs.next()) {
                UUID id = rs.getObject("id", UUID.class);
                builders.computeIfAbsent(id, ignored -> VariationBuilder.from(rs)).addRow(rs);
            }
            return builders;
        }, exerciseId);
        return mapped.values().stream().map(VariationBuilder::build).toList();
    }

    @Override
    public AdminExercise createDraft(UUID exerciseId, UUID actorId, ExerciseDraftData data, Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO fitness.exercises(
                    id, code, name, category_id, description, instructions, difficulty,
                    movement_pattern, unilateral, admin_status, created_by, created_at, updated_at, version
                ) VALUES (?, ?, ?,
                    (SELECT id FROM fitness.exercise_categories WHERE code = ?),
                    ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, 0)
                """,
                exerciseId, data.code(), data.name(), data.categoryCode(), data.description(), data.instructions(),
                data.difficulty(), data.movementPattern(), data.unilateral(), actorId,
                Timestamp.from(now), Timestamp.from(now)
        );
        insertDraftContent(exerciseId, data, now);
        return findById(exerciseId).orElseThrow();
    }

    @Override
    public boolean updateDraft(UUID exerciseId, long expectedVersion, ExerciseDraftData data, Instant now) {
        int updated = jdbcTemplate.update(
                """
                UPDATE fitness.exercises
                SET code = ?, name = ?,
                    category_id = (SELECT id FROM fitness.exercise_categories WHERE code = ?),
                    description = ?, instructions = ?, difficulty = ?, movement_pattern = ?, unilateral = ?,
                    updated_at = ?, version = version + 1
                WHERE id = ? AND admin_status = 'DRAFT' AND version = ? AND deleted_at IS NULL
                """,
                data.code(), data.name(), data.categoryCode(), data.description(), data.instructions(),
                data.difficulty(), data.movementPattern(), data.unilateral(), Timestamp.from(now),
                exerciseId, expectedVersion
        );
        if (updated == 0) {
            return false;
        }
        jdbcTemplate.update("DELETE FROM fitness.exercise_tag_assignments WHERE exercise_id = ?", exerciseId);
        jdbcTemplate.update("DELETE FROM fitness.exercise_variations WHERE exercise_id = ?", exerciseId);
        insertDraftContent(exerciseId, data, now);
        return true;
    }

    private void insertDraftContent(UUID exerciseId, ExerciseDraftData data, Instant now) {
        for (String tagCode : data.tagCodes()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO fitness.exercise_tag_assignments(exercise_id, exercise_tag_id, created_at)
                    SELECT ?, id, ? FROM fitness.exercise_tags WHERE code = ?
                    """,
                    exerciseId, Timestamp.from(now), tagCode
            );
        }
        for (AdminExerciseVariation variation : data.variations()) {
            UUID variationId = variation.id() == null ? UUID.randomUUID() : variation.id();
            jdbcTemplate.update(
                    """
                    INSERT INTO fitness.exercise_variations(
                        id, exercise_id, code, name, description, instructions, difficulty,
                        is_default, is_active, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    variationId, exerciseId, variation.code(), variation.name(), variation.description(),
                    variation.instructions(), variation.difficulty(), variation.defaultVariation(), variation.active(),
                    Timestamp.from(now), Timestamp.from(now)
            );
            for (AdminExerciseMuscle muscle : variation.muscles()) {
                jdbcTemplate.update(
                        """
                        INSERT INTO fitness.exercise_muscles(exercise_variation_id, muscle_group_id, involvement)
                        SELECT ?, id, ? FROM fitness.muscle_groups WHERE code = ?
                        """,
                        variationId, muscle.involvement(), muscle.muscleGroupCode()
                );
            }
            for (AdminExerciseEquipment equipment : variation.equipment()) {
                jdbcTemplate.update(
                        """
                        INSERT INTO fitness.exercise_equipment(exercise_variation_id, equipment_id, requirement)
                        SELECT ?, id, ? FROM fitness.equipment WHERE code = ?
                        """,
                        variationId, equipment.requirement(), equipment.equipmentCode()
                );
            }
        }
    }

    @Override
    public boolean transition(UUID exerciseId, long expectedVersion, ExerciseLifecycleStatus from,
                              ExerciseLifecycleStatus to, Instant now) {
        return jdbcTemplate.update(
                """
                UPDATE fitness.exercises
                SET admin_status = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND admin_status = ? AND version = ? AND deleted_at IS NULL
                """,
                to.name(), Timestamp.from(now), exerciseId, from.name(), expectedVersion
        ) == 1;
    }

    @Override
    public List<String> findDraftReferenceProblems(ExerciseDraftData data) {
        List<String> problems = new ArrayList<>();
        if (data.categoryCode() != null && !exists("exercise_categories", data.categoryCode(), false)) {
            problems.add("categoryCode");
        }
        for (String tag : new LinkedHashSet<>(data.tagCodes())) {
            if (!exists("exercise_tags", tag, true)) problems.add("tagCodes:" + tag);
        }
        Set<String> muscles = new LinkedHashSet<>();
        Set<String> equipment = new LinkedHashSet<>();
        data.variations().forEach(variation -> {
            variation.muscles().forEach(value -> muscles.add(value.muscleGroupCode()));
            variation.equipment().forEach(value -> equipment.add(value.equipmentCode()));
        });
        for (String muscle : muscles) {
            if (!exists("muscle_groups", muscle, false)) problems.add("muscleGroupCodes:" + muscle);
        }
        for (String item : equipment) {
            if (!exists("equipment", item, true)) problems.add("equipmentCodes:" + item);
        }
        return problems;
    }

    private boolean exists(String table, String code, boolean requireActive) {
        String sql = "SELECT EXISTS(SELECT 1 FROM fitness." + table + " WHERE code = ?" +
                (requireActive ? " AND is_active = true" : "") + ")";
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, code));
    }

    @Override
    public List<String> findActivationProblems(UUID exerciseId) {
        List<String> problems = new ArrayList<>();
        Boolean inactiveEquipment = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM fitness.exercise_variations v
                    JOIN fitness.exercise_equipment ee ON ee.exercise_variation_id = v.id
                    JOIN fitness.equipment e ON e.id = ee.equipment_id
                    WHERE v.exercise_id = ? AND v.is_active = true AND e.is_active = false
                )
                """, Boolean.class, exerciseId);
        Boolean inactiveTags = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM fitness.exercise_tag_assignments eta
                    JOIN fitness.exercise_tags t ON t.id = eta.exercise_tag_id
                    WHERE eta.exercise_id = ? AND t.is_active = false
                )
                """, Boolean.class, exerciseId);
        if (Boolean.TRUE.equals(inactiveEquipment)) problems.add("equipmentCodes");
        if (Boolean.TRUE.equals(inactiveTags)) problems.add("tagCodes");
        return problems;
    }

    @Override
    public boolean isCanonicalTarget(UUID exerciseId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM fitness.exercise_canonical_mappings WHERE canonical_exercise_id = ?)",
                Boolean.class, exerciseId));
    }

    @Override
    public boolean hasCanonicalReplacement(UUID exerciseId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM fitness.exercise_canonical_mappings WHERE duplicate_exercise_id = ?)",
                Boolean.class, exerciseId));
    }

    @Override
    public boolean setCanonicalReplacement(UUID sourceId, long expectedVersion, UUID targetId,
                                           UUID actorId, String reason, Instant now) {
        int updated = bumpVersion(sourceId, expectedVersion, now);
        if (updated == 0) return false;
        jdbcTemplate.update(
                """
                INSERT INTO fitness.exercise_canonical_mappings(
                    duplicate_exercise_id, canonical_exercise_id, mapped_by, reason, mapped_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (duplicate_exercise_id) DO UPDATE SET
                    canonical_exercise_id = EXCLUDED.canonical_exercise_id,
                    mapped_by = EXCLUDED.mapped_by,
                    reason = EXCLUDED.reason,
                    mapped_at = EXCLUDED.mapped_at
                """,
                sourceId, targetId, actorId, reason, Timestamp.from(now)
        );
        return true;
    }

    @Override
    public boolean clearCanonicalReplacement(UUID sourceId, long expectedVersion, Instant now) {
        int updated = bumpVersion(sourceId, expectedVersion, now);
        if (updated == 0) return false;
        jdbcTemplate.update(
                "DELETE FROM fitness.exercise_canonical_mappings WHERE duplicate_exercise_id = ?",
                sourceId
        );
        return true;
    }

    private int bumpVersion(UUID sourceId, long expectedVersion, Instant now) {
        return jdbcTemplate.update(
                """
                UPDATE fitness.exercises
                SET version = version + 1, updated_at = ?
                WHERE id = ? AND admin_status = 'ARCHIVED' AND version = ? AND deleted_at IS NULL
                """,
                Timestamp.from(now), sourceId, expectedVersion
        );
    }

    private static BaseExercise mapBase(ResultSet rs) throws SQLException {
        return new BaseExercise(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("category_code"), rs.getString("description"), rs.getString("instructions"),
                rs.getString("difficulty"), rs.getString("movement_pattern"), rs.getBoolean("unilateral"),
                ExerciseLifecycleStatus.valueOf(rs.getString("admin_status")), rs.getLong("version"),
                rs.getObject("canonical_exercise_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private record BaseExercise(
            UUID id, String code, String name, String categoryCode, String description, String instructions,
            String difficulty, String movementPattern, boolean unilateral, ExerciseLifecycleStatus status,
            long version, UUID canonicalReplacementId, Instant createdAt, Instant updatedAt
    ) {
        AdminExercise toDomain(List<String> tags, List<AdminExerciseVariation> variations) {
            return new AdminExercise(id, code, name, categoryCode, description, instructions, difficulty,
                    movementPattern, unilateral, status, version, canonicalReplacementId, tags, variations,
                    createdAt, updatedAt);
        }
    }

    private static final class VariationBuilder {
        private final UUID id;
        private final String code;
        private final String name;
        private final String description;
        private final String instructions;
        private final String difficulty;
        private final boolean defaultVariation;
        private final boolean active;
        private final Map<String, AdminExerciseMuscle> muscles = new LinkedHashMap<>();
        private final Map<String, AdminExerciseEquipment> equipment = new LinkedHashMap<>();

        private VariationBuilder(ResultSet rs) throws SQLException {
            id = rs.getObject("id", UUID.class);
            code = rs.getString("code");
            name = rs.getString("name");
            description = rs.getString("description");
            instructions = rs.getString("instructions");
            difficulty = rs.getString("difficulty");
            defaultVariation = rs.getBoolean("is_default");
            active = rs.getBoolean("is_active");
        }

        static VariationBuilder from(ResultSet rs) {
            try {
                return new VariationBuilder(rs);
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not map Admin Exercise variation", exception);
            }
        }

        void addRow(ResultSet rs) throws SQLException {
            String muscleCode = rs.getString("muscle_code");
            String involvement = rs.getString("involvement");
            if (muscleCode != null) muscles.put(muscleCode + '|' + involvement,
                    new AdminExerciseMuscle(muscleCode, involvement));
            String equipmentCode = rs.getString("equipment_code");
            String requirement = rs.getString("requirement");
            if (equipmentCode != null) equipment.put(equipmentCode + '|' + requirement,
                    new AdminExerciseEquipment(equipmentCode, requirement));
        }

        AdminExerciseVariation build() {
            return new AdminExerciseVariation(id, code, name, description, instructions, difficulty,
                    defaultVariation, active, new ArrayList<>(muscles.values()),
                    new ArrayList<>(equipment.values()));
        }
    }
}
