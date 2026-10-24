package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class JdbcWorkoutCompletionEvidenceQueryTest {
    @Test void studentReadFiltersInSqlBeforeMappingRows() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        UUID student=UUID.randomUUID();
        when(jdbc.query(anyString(),any(RowMapper.class),eq(student))).thenReturn(List.of());

        assertThat(new JdbcWorkoutCompletionEvidenceQuery(jdbc).completedForStudent(student)).isEmpty();

        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),eq(student));
        assertThat(sql.getValue()).contains("WHERE student_id=? AND status='COMPLETED'")
                .contains("ORDER BY COALESCE(performed_end_at,performed_start_at),id");
    }
}
