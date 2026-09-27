package com.fitnesscoaching.platform.modules.exercise.application.service;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.ExerciseNotFoundException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.modules.exercise.application.model.ExerciseCatalogPage;
import com.fitnesscoaching.platform.modules.exercise.application.model.ExerciseFilterMetadata;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseCatalogQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseCatalogUseCase;
import com.fitnesscoaching.platform.modules.exercise.application.port.out.ExerciseCatalogRepository;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseCatalogDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ExerciseCatalogService implements ExerciseCatalogUseCase {

    static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_FILTER_VALUES = 20;
    private static final int MAX_QUERY_LENGTH = 120;

    private final ExerciseCatalogRepository repository;

    public ExerciseCatalogService(ExerciseCatalogRepository repository) {
        this.repository = repository;
    }

    @Override
    public ExerciseCatalogPage search(ExerciseCatalogQuery query) {
        ExerciseCatalogQuery normalized = normalizeAndValidate(query);
        return repository.search(normalized);
    }

    @Override
    public ExerciseCatalogDetail getDetail(UUID exerciseId) {
        if (exerciseId == null) {
            throw validation("exerciseId", "NotNull", "Exercise ID is required");
        }
        return repository.findActiveDetail(exerciseId)
                .orElseThrow(() -> new ExerciseNotFoundException("Exercise was not found."));
    }

    @Override
    public ExerciseFilterMetadata getFilterMetadata() {
        return repository.findFilterMetadata();
    }

    private ExerciseCatalogQuery normalizeAndValidate(ExerciseCatalogQuery query) {
        if (query == null) {
            throw validation("query", "NotNull", "Catalog query is required");
        }
        if (query.page() < 0) {
            throw validation("page", "Min", "Page must be zero or greater");
        }
        if (query.size() < 1 || query.size() > MAX_PAGE_SIZE) {
            throw validation("size", "Range", "Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if ((long) query.page() * query.size() > Integer.MAX_VALUE) {
            throw validation("page", "Range", "Requested page is outside the supported range");
        }

        String search = trimToNull(query.query());
        if (search != null && search.length() > MAX_QUERY_LENGTH) {
            throw validation("query", "Size", "Search query must not exceed " + MAX_QUERY_LENGTH + " characters");
        }

        return new ExerciseCatalogQuery(
                search,
                normalizeCodes(query.categoryCodes(), "categoryCodes"),
                normalizeCodes(query.muscleGroupCodes(), "muscleGroupCodes"),
                normalizeCodes(query.equipmentCodes(), "equipmentCodes"),
                normalizeCodes(query.tagCodes(), "tagCodes"),
                normalizeCodes(query.difficulties(), "difficulties"),
                normalizeCodes(query.movementPatterns(), "movementPatterns"),
                query.page(),
                query.size()
        );
    }

    private List<String> normalizeCodes(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_FILTER_VALUES) {
            throw validation(field, "Size", "At most " + MAX_FILTER_VALUES + " filter values are allowed");
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed == null) {
                throw validation(field, "NotBlank", "Filter values must not be blank");
            }
            normalized.add(trimmed.toUpperCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ApplicationValidationException validation(String field, String code, String message) {
        return new ApplicationValidationException(message, List.of(new FieldErrorDto(field, code, message)));
    }
}
