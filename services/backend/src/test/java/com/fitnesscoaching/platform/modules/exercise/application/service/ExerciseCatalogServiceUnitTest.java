package com.fitnesscoaching.platform.modules.exercise.application.service;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.ExerciseNotFoundException;
import com.fitnesscoaching.platform.modules.exercise.application.model.ExerciseCatalogPage;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseCatalogQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.out.ExerciseCatalogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExerciseCatalogServiceUnitTest {

    @Mock
    private ExerciseCatalogRepository repository;

    @Test
    void search_normalizesAndDeduplicatesFilters() {
        ExerciseCatalogService service = new ExerciseCatalogService(repository);
        when(repository.search(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ExerciseCatalogPage(List.of(), 0, 20, 0, 0));

        service.search(new ExerciseCatalogQuery(
                "  squat  ",
                List.of(" strength ", "STRENGTH"),
                List.of("quadriceps"),
                null,
                List.of(),
                List.of("beginner"),
                List.of("squat"),
                0,
                20
        ));

        ArgumentCaptor<ExerciseCatalogQuery> captor = ArgumentCaptor.forClass(ExerciseCatalogQuery.class);
        verify(repository).search(captor.capture());
        ExerciseCatalogQuery normalized = captor.getValue();
        assertThat(normalized.query()).isEqualTo("squat");
        assertThat(normalized.categoryCodes()).containsExactly("STRENGTH");
        assertThat(normalized.muscleGroupCodes()).containsExactly("QUADRICEPS");
        assertThat(normalized.difficulties()).containsExactly("BEGINNER");
        assertThat(normalized.movementPatterns()).containsExactly("SQUAT");
        assertThat(normalized.equipmentCodes()).isEmpty();
    }

    @Test
    void search_rejectsInvalidPaginationAndOversizedFilter() {
        ExerciseCatalogService service = new ExerciseCatalogService(repository);

        assertThatThrownBy(() -> service.search(queryWithPage(-1, 20)))
                .isInstanceOf(ApplicationValidationException.class);
        assertThatThrownBy(() -> service.search(queryWithPage(0, 101)))
                .isInstanceOf(ApplicationValidationException.class);
        assertThatThrownBy(() -> service.search(queryWithPage(Integer.MAX_VALUE, 100)))
                .isInstanceOf(ApplicationValidationException.class);
        assertThatThrownBy(() -> service.search(new ExerciseCatalogQuery(
                null,
                java.util.stream.IntStream.range(0, 21).mapToObj(index -> "C" + index).toList(),
                null, null, null, null, null, 0, 20
        ))).isInstanceOf(ApplicationValidationException.class);
    }

    @Test
    void getDetail_hidesMissingOrNonVisibleExerciseBehindNotFound() {
        ExerciseCatalogRepository repository = org.mockito.Mockito.mock(ExerciseCatalogRepository.class);
        ExerciseCatalogService service = new ExerciseCatalogService(repository);
        UUID exerciseId = UUID.randomUUID();
        when(repository.findActiveDetail(exerciseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(exerciseId))
                .isInstanceOf(ExerciseNotFoundException.class)
                .hasMessage("Exercise was not found.");
    }

    private ExerciseCatalogQuery queryWithPage(int page, int size) {
        return new ExerciseCatalogQuery(null, null, null, null, null, null, null, page, size);
    }
}
