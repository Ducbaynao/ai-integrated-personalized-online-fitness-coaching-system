package com.fitnesscoaching.platform.modules.exercise.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.exercise.application.model.ExerciseCatalogPage;
import com.fitnesscoaching.platform.modules.exercise.application.model.ExerciseFilterMetadata;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseCatalogQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.out.ExerciseCatalogRepository;
import com.fitnesscoaching.platform.modules.exercise.domain.CatalogOption;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseCatalogDetail;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseCatalogItem;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseEquipment;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseGuidance;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseMediaReference;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseMuscle;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseVariation;
import com.fitnesscoaching.platform.modules.exercise.domain.MuscleGroupOption;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Repository
public class JdbcExerciseCatalogRepository implements ExerciseCatalogRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcExerciseCatalogRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ExerciseCatalogPage search(ExerciseCatalogQuery query) {
        FilterSql filter = buildFilter(query);
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.exercises e " + filter.whereClause(),
                filter.parameters(),
                Long.class
        );

        MapSqlParameterSource pageParameters = new MapSqlParameterSource(filter.parameters().getValues())
                .addValue("limit", query.size())
                .addValue("offset", Math.multiplyExact(query.page(), query.size()));

        String sql = """
                WITH selected AS (
                    SELECT e.id, e.code, e.name, e.category_id, e.description, e.difficulty,
                           e.movement_pattern, e.unilateral
                    FROM fitness.exercises e
                """ + filter.whereClause() + """
                    ORDER BY lower(e.name) ASC, e.id ASC
                    LIMIT :limit OFFSET :offset
                )
                SELECT s.id, s.code, s.name, s.description, s.difficulty, s.movement_pattern, s.unilateral,
                       c.code AS category_code, c.name AS category_name,
                       v.id AS variation_id,
                       mg.code AS muscle_code, mg.name AS muscle_name,
                       eq.code AS equipment_code, eq.name AS equipment_name,
                       t.code AS tag_code, t.name AS tag_name,
                       CASE WHEN mf.id IS NOT NULL AND mf.deleted_at IS NULL AND mf.scan_status = 'CLEAN'
                            THEN true ELSE false END AS media_available
                FROM selected s
                LEFT JOIN fitness.exercise_categories c ON c.id = s.category_id
                LEFT JOIN fitness.exercise_variations v ON v.exercise_id = s.id AND v.is_active = true
                LEFT JOIN fitness.exercise_muscles em ON em.exercise_variation_id = v.id AND em.involvement = 'PRIMARY'
                LEFT JOIN fitness.muscle_groups mg ON mg.id = em.muscle_group_id
                LEFT JOIN fitness.exercise_equipment ee ON ee.exercise_variation_id = v.id
                LEFT JOIN fitness.equipment eq ON eq.id = ee.equipment_id
                LEFT JOIN fitness.exercise_tag_assignments eta ON eta.exercise_id = s.id
                LEFT JOIN fitness.exercise_tags t ON t.id = eta.exercise_tag_id AND t.is_active = true
                LEFT JOIN fitness.exercise_media xm ON xm.exercise_variation_id = v.id
                LEFT JOIN fitness.media_files mf ON mf.id = xm.media_id
                ORDER BY lower(s.name) ASC, s.id ASC, v.id ASC, mg.code ASC, eq.code ASC, t.code ASC
                """;

        Map<UUID, ItemBuilder> builders = jdbcTemplate.query(sql, pageParameters, resultSet -> {
            Map<UUID, ItemBuilder> mapped = new LinkedHashMap<>();
            while (resultSet.next()) {
                UUID id = resultSet.getObject("id", UUID.class);
                ItemBuilder builder = mapped.get(id);
                if (builder == null) {
                    builder = ItemBuilder.from(resultSet);
                    mapped.put(id, builder);
                }
                builder.addRow(resultSet);
            }
            return mapped;
        });

        List<ExerciseCatalogItem> items = builders.values().stream().map(ItemBuilder::build).toList();
        long totalItems = total != null ? total : 0L;
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + query.size() - 1) / query.size());
        return new ExerciseCatalogPage(items, query.page(), query.size(), totalItems, totalPages);
    }

    @Override
    public Optional<ExerciseCatalogDetail> findActiveDetail(UUID exerciseId) {
        String baseSql = """
                SELECT e.id, e.code, e.name, e.description, e.instructions, e.difficulty,
                       e.movement_pattern, e.unilateral,
                       c.code AS category_code, c.name AS category_name
                FROM fitness.exercises e
                LEFT JOIN fitness.exercise_categories c ON c.id = e.category_id
                WHERE e.id = :exerciseId
                  AND e.admin_status = 'ACTIVE'
                  AND e.deleted_at IS NULL
                """;

        List<DetailBuilder> base = jdbcTemplate.query(
                baseSql,
                new MapSqlParameterSource("exerciseId", exerciseId),
                (resultSet, rowNumber) -> DetailBuilder.from(resultSet)
        );
        if (base.isEmpty()) {
            return Optional.empty();
        }

        DetailBuilder detail = base.getFirst();
        loadVariations(exerciseId, detail);
        loadTags(exerciseId, detail);
        loadGuidance(exerciseId, detail);
        return Optional.of(detail.build());
    }

    @Override
    public ExerciseFilterMetadata findFilterMetadata() {
        String activeExercise = " e.admin_status = 'ACTIVE' AND e.deleted_at IS NULL ";

        List<CatalogOption> categories = jdbcTemplate.query("""
                SELECT DISTINCT c.code, c.name
                FROM fitness.exercise_categories c
                JOIN fitness.exercises e ON e.category_id = c.id
                WHERE """ + activeExercise + " ORDER BY c.name, c.code",
                (resultSet, rowNumber) -> option(resultSet, "code", "name"));

        List<MuscleGroupOption> muscleGroups = jdbcTemplate.query("""
                SELECT DISTINCT mg.code, mg.name, parent.code AS parent_code
                FROM fitness.muscle_groups mg
                LEFT JOIN fitness.muscle_groups parent ON parent.id = mg.parent_id
                JOIN fitness.exercise_muscles em ON em.muscle_group_id = mg.id
                JOIN fitness.exercise_variations v ON v.id = em.exercise_variation_id AND v.is_active = true
                JOIN fitness.exercises e ON e.id = v.exercise_id
                WHERE """ + activeExercise + " ORDER BY mg.name, mg.code",
                (resultSet, rowNumber) -> new MuscleGroupOption(
                        resultSet.getString("code"),
                        resultSet.getString("name"),
                        resultSet.getString("parent_code")
                ));

        List<CatalogOption> equipment = jdbcTemplate.query("""
                SELECT DISTINCT eq.code, eq.name
                FROM fitness.equipment eq
                JOIN fitness.exercise_equipment ee ON ee.equipment_id = eq.id
                JOIN fitness.exercise_variations v ON v.id = ee.exercise_variation_id AND v.is_active = true
                JOIN fitness.exercises e ON e.id = v.exercise_id
                WHERE eq.is_active = true AND """ + activeExercise + " ORDER BY eq.name, eq.code",
                (resultSet, rowNumber) -> option(resultSet, "code", "name"));

        List<CatalogOption> tags = jdbcTemplate.query("""
                SELECT DISTINCT t.code, t.name
                FROM fitness.exercise_tags t
                JOIN fitness.exercise_tag_assignments eta ON eta.exercise_tag_id = t.id
                JOIN fitness.exercises e ON e.id = eta.exercise_id
                WHERE t.is_active = true AND """ + activeExercise + " ORDER BY t.name, t.code",
                (resultSet, rowNumber) -> option(resultSet, "code", "name"));

        List<String> difficulties = jdbcTemplate.queryForList("""
                SELECT DISTINCT e.difficulty
                FROM fitness.exercises e
                WHERE e.difficulty IS NOT NULL AND """ + activeExercise + " ORDER BY e.difficulty",
                Map.of(),
                String.class);

        List<String> movementPatterns = jdbcTemplate.queryForList("""
                SELECT DISTINCT e.movement_pattern
                FROM fitness.exercises e
                WHERE e.movement_pattern IS NOT NULL AND """ + activeExercise + " ORDER BY e.movement_pattern",
                Map.of(),
                String.class);

        return new ExerciseFilterMetadata(categories, muscleGroups, equipment, tags, difficulties, movementPatterns);
    }

    private void loadVariations(UUID exerciseId, DetailBuilder detail) {
        String sql = """
                SELECT v.id, v.code, v.name, v.description, v.instructions, v.difficulty, v.is_default,
                       mg.code AS muscle_code, mg.name AS muscle_name, em.involvement,
                       eq.code AS equipment_code, eq.name AS equipment_name, ee.requirement,
                       xm.media_id, xm.purpose AS media_purpose, xm.sort_order AS media_sort_order,
                       mf.content_type, mf.size_bytes,
                       CASE WHEN mf.id IS NOT NULL AND mf.deleted_at IS NULL AND mf.scan_status = 'CLEAN'
                            THEN true ELSE false END AS media_available
                FROM fitness.exercise_variations v
                LEFT JOIN fitness.exercise_muscles em ON em.exercise_variation_id = v.id
                LEFT JOIN fitness.muscle_groups mg ON mg.id = em.muscle_group_id
                LEFT JOIN fitness.exercise_equipment ee ON ee.exercise_variation_id = v.id
                LEFT JOIN fitness.equipment eq ON eq.id = ee.equipment_id
                LEFT JOIN fitness.exercise_media xm ON xm.exercise_variation_id = v.id
                LEFT JOIN fitness.media_files mf ON mf.id = xm.media_id
                WHERE v.exercise_id = :exerciseId AND v.is_active = true
                ORDER BY v.is_default DESC, v.name, v.id, em.involvement, mg.code, eq.code, xm.sort_order, xm.id
                """;

        jdbcTemplate.query(sql, new MapSqlParameterSource("exerciseId", exerciseId), resultSet -> {
            while (resultSet.next()) {
                detail.addVariationRow(resultSet);
            }
            return null;
        });
    }

    private void loadTags(UUID exerciseId, DetailBuilder detail) {
        String sql = """
                SELECT t.code, t.name
                FROM fitness.exercise_tags t
                JOIN fitness.exercise_tag_assignments eta ON eta.exercise_tag_id = t.id
                WHERE eta.exercise_id = :exerciseId AND t.is_active = true
                ORDER BY t.name, t.code
                """;
        jdbcTemplate.query(sql, new MapSqlParameterSource("exerciseId", exerciseId), resultSet -> {
            while (resultSet.next()) {
                detail.tags.put(resultSet.getString("code"), option(resultSet, "code", "name"));
            }
            return null;
        });
    }

    private void loadGuidance(UUID exerciseId, DetailBuilder detail) {
        String sql = """
                SELECT g.id, g.exercise_variation_id, g.guidance_type, g.title, g.description,
                       g.correction, g.severity, g.sort_order
                FROM fitness.exercise_guidance g
                LEFT JOIN fitness.exercise_variations v
                       ON v.id = g.exercise_variation_id AND v.is_active = true
                WHERE g.exercise_id = :exerciseId
                  AND g.status = 'ACTIVE'
                  AND (g.exercise_variation_id IS NULL OR v.id IS NOT NULL)
                ORDER BY g.sort_order, g.id
                """;
        detail.guidance.addAll(jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("exerciseId", exerciseId),
                (resultSet, rowNumber) -> new ExerciseGuidance(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("exercise_variation_id", UUID.class),
                        resultSet.getString("guidance_type"),
                        resultSet.getString("title"),
                        resultSet.getString("description"),
                        resultSet.getString("correction"),
                        resultSet.getString("severity"),
                        resultSet.getInt("sort_order")
                )
        ));
    }

    private FilterSql buildFilter(ExerciseCatalogQuery query) {
        StringBuilder where = new StringBuilder(" WHERE e.admin_status = 'ACTIVE' AND e.deleted_at IS NULL ");
        MapSqlParameterSource parameters = new MapSqlParameterSource();

        if (query.query() != null) {
            where.append("""
                    AND (
                        lower(e.name) LIKE :search ESCAPE '!'
                        OR lower(e.code) LIKE :search ESCAPE '!'
                        OR lower(COALESCE(e.description, '')) LIKE :search ESCAPE '!'
                        OR EXISTS (
                            SELECT 1 FROM fitness.exercise_variations sv
                            WHERE sv.exercise_id = e.id AND sv.is_active = true
                              AND (lower(sv.name) LIKE :search ESCAPE '!'
                                   OR lower(sv.code) LIKE :search ESCAPE '!')
                        )
                    )
                    """);
            parameters.addValue("search", "%" + escapeLike(query.query().toLowerCase(Locale.ROOT)) + "%");
        }
        addDirectFilter(where, parameters, "categoryCodes", query.categoryCodes(),
                "EXISTS (SELECT 1 FROM fitness.exercise_categories fc WHERE fc.id = e.category_id AND upper(fc.code) IN (:categoryCodes))");
        addVariationFilter(where, parameters, "muscleGroupCodes", query.muscleGroupCodes(), """
                EXISTS (
                    SELECT 1 FROM fitness.exercise_variations fv
                    JOIN fitness.exercise_muscles fem ON fem.exercise_variation_id = fv.id
                    JOIN fitness.muscle_groups fmg ON fmg.id = fem.muscle_group_id
                    WHERE fv.exercise_id = e.id AND fv.is_active = true
                      AND upper(fmg.code) IN (:muscleGroupCodes)
                )
                """);
        addVariationFilter(where, parameters, "equipmentCodes", query.equipmentCodes(), """
                EXISTS (
                    SELECT 1 FROM fitness.exercise_variations fv
                    JOIN fitness.exercise_equipment fee ON fee.exercise_variation_id = fv.id
                    JOIN fitness.equipment feq ON feq.id = fee.equipment_id
                    WHERE fv.exercise_id = e.id AND fv.is_active = true
                      AND upper(feq.code) IN (:equipmentCodes)
                )
                """);
        addDirectFilter(where, parameters, "tagCodes", query.tagCodes(), """
                EXISTS (
                    SELECT 1 FROM fitness.exercise_tag_assignments feta
                    JOIN fitness.exercise_tags ft ON ft.id = feta.exercise_tag_id
                    WHERE feta.exercise_id = e.id AND ft.is_active = true
                      AND upper(ft.code) IN (:tagCodes)
                )
                """);
        addDirectFilter(where, parameters, "difficulties", query.difficulties(),
                "upper(e.difficulty) IN (:difficulties)");
        addDirectFilter(where, parameters, "movementPatterns", query.movementPatterns(),
                "upper(e.movement_pattern) IN (:movementPatterns)");

        return new FilterSql(where.toString(), parameters);
    }

    private void addDirectFilter(
            StringBuilder where,
            MapSqlParameterSource parameters,
            String parameterName,
            List<String> values,
            String predicate
    ) {
        if (values != null && !values.isEmpty()) {
            where.append(" AND ").append(predicate).append(' ');
            parameters.addValue(parameterName, values);
        }
    }

    private void addVariationFilter(
            StringBuilder where,
            MapSqlParameterSource parameters,
            String parameterName,
            List<String> values,
            String predicate
    ) {
        addDirectFilter(where, parameters, parameterName, values, predicate);
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static CatalogOption option(ResultSet resultSet, String codeColumn, String nameColumn) throws SQLException {
        return new CatalogOption(resultSet.getString(codeColumn), resultSet.getString(nameColumn));
    }

    private record FilterSql(String whereClause, MapSqlParameterSource parameters) {
    }

    private static final class ItemBuilder {
        private final UUID id;
        private final String code;
        private final String name;
        private final CatalogOption category;
        private final String description;
        private final String difficulty;
        private final String movementPattern;
        private final boolean unilateral;
        private final Map<String, CatalogOption> primaryMuscles = new TreeMap<>();
        private final Map<String, CatalogOption> equipment = new TreeMap<>();
        private final Map<String, CatalogOption> tags = new TreeMap<>();
        private final Map<UUID, Boolean> variations = new LinkedHashMap<>();
        private boolean mediaAvailable;

        private ItemBuilder(
                UUID id,
                String code,
                String name,
                CatalogOption category,
                String description,
                String difficulty,
                String movementPattern,
                boolean unilateral
        ) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.category = category;
            this.description = description;
            this.difficulty = difficulty;
            this.movementPattern = movementPattern;
            this.unilateral = unilateral;
        }

        static ItemBuilder from(ResultSet resultSet) throws SQLException {
            String categoryCode = resultSet.getString("category_code");
            CatalogOption category = categoryCode == null
                    ? null
                    : new CatalogOption(categoryCode, resultSet.getString("category_name"));
            return new ItemBuilder(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("code"),
                    resultSet.getString("name"),
                    category,
                    resultSet.getString("description"),
                    resultSet.getString("difficulty"),
                    resultSet.getString("movement_pattern"),
                    resultSet.getBoolean("unilateral")
            );
        }

        void addRow(ResultSet resultSet) throws SQLException {
            UUID variationId = resultSet.getObject("variation_id", UUID.class);
            if (variationId != null) {
                variations.put(variationId, Boolean.TRUE);
            }
            addOption(resultSet, "muscle_code", "muscle_name", primaryMuscles);
            addOption(resultSet, "equipment_code", "equipment_name", equipment);
            addOption(resultSet, "tag_code", "tag_name", tags);
            mediaAvailable = mediaAvailable || resultSet.getBoolean("media_available");
        }

        ExerciseCatalogItem build() {
            return new ExerciseCatalogItem(
                    id, code, name, category, description, difficulty, movementPattern, unilateral,
                    new ArrayList<>(primaryMuscles.values()),
                    new ArrayList<>(equipment.values()),
                    new ArrayList<>(tags.values()),
                    variations.size(),
                    mediaAvailable
            );
        }
    }

    private static final class DetailBuilder {
        private final UUID id;
        private final String code;
        private final String name;
        private final CatalogOption category;
        private final String description;
        private final String instructions;
        private final String difficulty;
        private final String movementPattern;
        private final boolean unilateral;
        private final Map<UUID, VariationBuilder> variations = new LinkedHashMap<>();
        private final Map<String, CatalogOption> tags = new TreeMap<>();
        private final List<ExerciseGuidance> guidance = new ArrayList<>();

        private DetailBuilder(
                UUID id,
                String code,
                String name,
                CatalogOption category,
                String description,
                String instructions,
                String difficulty,
                String movementPattern,
                boolean unilateral
        ) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.category = category;
            this.description = description;
            this.instructions = instructions;
            this.difficulty = difficulty;
            this.movementPattern = movementPattern;
            this.unilateral = unilateral;
        }

        static DetailBuilder from(ResultSet resultSet) throws SQLException {
            String categoryCode = resultSet.getString("category_code");
            CatalogOption category = categoryCode == null
                    ? null
                    : new CatalogOption(categoryCode, resultSet.getString("category_name"));
            return new DetailBuilder(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("code"),
                    resultSet.getString("name"),
                    category,
                    resultSet.getString("description"),
                    resultSet.getString("instructions"),
                    resultSet.getString("difficulty"),
                    resultSet.getString("movement_pattern"),
                    resultSet.getBoolean("unilateral")
            );
        }

        void addVariationRow(ResultSet resultSet) throws SQLException {
            UUID variationId = resultSet.getObject("id", UUID.class);
            VariationBuilder variation = variations.computeIfAbsent(variationId, ignored -> VariationBuilder.from(resultSet));
            variation.addRow(resultSet);
        }

        ExerciseCatalogDetail build() {
            List<ExerciseVariation> mappedVariations = variations.values().stream().map(VariationBuilder::build).toList();
            boolean mediaAvailable = mappedVariations.stream()
                    .flatMap(variation -> variation.media().stream())
                    .anyMatch(ExerciseMediaReference::available);
            return new ExerciseCatalogDetail(
                    id, code, name, category, description, instructions, difficulty, movementPattern, unilateral,
                    mappedVariations,
                    new ArrayList<>(tags.values()),
                    guidance,
                    mediaAvailable
            );
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
        private final Map<String, ExerciseMuscle> muscles = new TreeMap<>();
        private final Map<String, ExerciseEquipment> equipment = new TreeMap<>();
        private final Map<String, ExerciseMediaReference> media = new TreeMap<>();

        private VariationBuilder(
                UUID id,
                String code,
                String name,
                String description,
                String instructions,
                String difficulty,
                boolean defaultVariation
        ) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.description = description;
            this.instructions = instructions;
            this.difficulty = difficulty;
            this.defaultVariation = defaultVariation;
        }

        static VariationBuilder from(ResultSet resultSet) {
            try {
                return new VariationBuilder(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("code"),
                        resultSet.getString("name"),
                        resultSet.getString("description"),
                        resultSet.getString("instructions"),
                        resultSet.getString("difficulty"),
                        resultSet.getBoolean("is_default")
                );
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not map exercise variation", exception);
            }
        }

        void addRow(ResultSet resultSet) throws SQLException {
            String muscleCode = resultSet.getString("muscle_code");
            if (muscleCode != null) {
                String involvement = resultSet.getString("involvement");
                muscles.put(muscleCode + '|' + involvement, new ExerciseMuscle(
                        muscleCode,
                        resultSet.getString("muscle_name"),
                        involvement
                ));
            }

            String equipmentCode = resultSet.getString("equipment_code");
            if (equipmentCode != null) {
                String requirement = resultSet.getString("requirement");
                equipment.put(equipmentCode + '|' + requirement, new ExerciseEquipment(
                        equipmentCode,
                        resultSet.getString("equipment_name"),
                        requirement
                ));
            }

            UUID mediaId = resultSet.getObject("media_id", UUID.class);
            if (mediaId != null) {
                String purpose = resultSet.getString("media_purpose");
                long size = resultSet.getLong("size_bytes");
                Long sizeBytes = resultSet.wasNull() ? null : size;
                media.put(mediaId + "|" + purpose, new ExerciseMediaReference(
                        mediaId,
                        purpose,
                        resultSet.getInt("media_sort_order"),
                        resultSet.getString("content_type"),
                        sizeBytes,
                        resultSet.getBoolean("media_available")
                ));
            }
        }

        ExerciseVariation build() {
            return new ExerciseVariation(
                    id, code, name, description, instructions, difficulty, defaultVariation,
                    new ArrayList<>(muscles.values()),
                    new ArrayList<>(equipment.values()),
                    new ArrayList<>(media.values())
            );
        }
    }

    private static void addOption(
            ResultSet resultSet,
            String codeColumn,
            String nameColumn,
            Map<String, CatalogOption> destination
    ) throws SQLException {
        String code = resultSet.getString(codeColumn);
        if (code != null) {
            destination.put(code, new CatalogOption(code, resultSet.getString(nameColumn)));
        }
    }
}
