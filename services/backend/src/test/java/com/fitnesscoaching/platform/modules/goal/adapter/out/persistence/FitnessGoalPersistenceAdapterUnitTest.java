package com.fitnesscoaching.platform.modules.goal.adapter.out.persistence;

import com.fitnesscoaching.platform.common.exception.GoalLifecycleConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FitnessGoalPersistenceAdapterUnitTest {

    private JdbcTemplate jdbcTemplate;
    private FitnessGoalPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        adapter = new FitnessGoalPersistenceAdapter(jdbcTemplate);
    }

    @Test
    @DisplayName("pauseGoal: CAS UPDATE binds student_id and requires deleted_at IS NULL")
    void pauseGoal_bindsStudentIdAndDeletedAtCheck() {
        UUID goalId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        String reason = "Wrist sprain";
        Instant pausedAt = Instant.parse("2026-10-15T10:00:00Z");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);

        when(jdbcTemplate.update(sqlCaptor.capture(), (Object[]) argsCaptor.capture()))
                .thenReturn(1);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(goalId)))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> adapter.pauseGoal(goalId, studentId, reason, pausedAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to load paused fitness goal");

        String updateSql = sqlCaptor.getAllValues().get(0);
        assertThat(updateSql)
                .contains("WHERE id = ?")
                .contains("AND student_id = ?")
                .contains("AND status = 'ACTIVE'::fitness.lifecycle_status")
                .contains("AND deleted_at IS NULL");

        Object[] updateArgs = argsCaptor.getAllValues().get(0);
        assertThat(updateArgs).hasSize(5);
        assertThat(updateArgs[0]).isEqualTo(Timestamp.from(pausedAt));
        assertThat(updateArgs[1]).isEqualTo(reason);
        assertThat(updateArgs[2]).isEqualTo(Timestamp.from(pausedAt));
        assertThat(updateArgs[3]).isEqualTo(goalId);
        assertThat(updateArgs[4]).isEqualTo(studentId);
    }

    @Test
    @DisplayName("pauseGoal: 0 rows affected (wrong student_id or stale status) throws GoalLifecycleConflictException")
    void pauseGoal_zeroRowsAffected_throwsGoalLifecycleConflictException() {
        UUID goalId = UUID.randomUUID();
        UUID wrongStudentId = UUID.randomUUID();
        Instant pausedAt = Instant.parse("2026-10-15T10:00:00Z");

        when(jdbcTemplate.update(anyString(), any(), any(), any(), eq(goalId), eq(wrongStudentId)))
                .thenReturn(0);

        assertThatThrownBy(() -> adapter.pauseGoal(goalId, wrongStudentId, "Pause attempt", pausedAt))
                .isInstanceOf(GoalLifecycleConflictException.class)
                .hasMessageContaining("Cannot pause fitness goal: goal status is no longer ACTIVE or was concurrently modified");

        verify(jdbcTemplate, never()).update(
                org.mockito.ArgumentMatchers.contains("fitness_goal_status_history"),
                any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("resumeGoal: CAS UPDATE binds student_id and requires deleted_at IS NULL")
    void resumeGoal_bindsStudentIdAndDeletedAtCheck() {
        UUID goalId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        String reason = "Wrist healed";
        Instant resumedAt = Instant.parse("2026-10-25T10:00:00Z");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);

        when(jdbcTemplate.update(sqlCaptor.capture(), (Object[]) argsCaptor.capture()))
                .thenReturn(1);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(goalId)))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> adapter.resumeGoal(goalId, studentId, reason, resumedAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to load resumed fitness goal");

        String updateSql = sqlCaptor.getAllValues().get(0);
        assertThat(updateSql)
                .contains("WHERE id = ?")
                .contains("AND student_id = ?")
                .contains("AND status = 'PAUSED'::fitness.lifecycle_status")
                .contains("AND deleted_at IS NULL");

        Object[] updateArgs = argsCaptor.getAllValues().get(0);
        assertThat(updateArgs).hasSize(4);
        assertThat(updateArgs[0]).isEqualTo(reason);
        assertThat(updateArgs[1]).isEqualTo(Timestamp.from(resumedAt));
        assertThat(updateArgs[2]).isEqualTo(goalId);
        assertThat(updateArgs[3]).isEqualTo(studentId);
    }

    @Test
    @DisplayName("resumeGoal: 0 rows affected (wrong student_id or stale status) throws GoalLifecycleConflictException")
    void resumeGoal_zeroRowsAffected_throwsGoalLifecycleConflictException() {
        UUID goalId = UUID.randomUUID();
        UUID wrongStudentId = UUID.randomUUID();
        Instant resumedAt = Instant.parse("2026-10-25T10:00:00Z");

        when(jdbcTemplate.update(anyString(), any(), any(), eq(goalId), eq(wrongStudentId)))
                .thenReturn(0);

        assertThatThrownBy(() -> adapter.resumeGoal(goalId, wrongStudentId, "Resume attempt", resumedAt))
                .isInstanceOf(GoalLifecycleConflictException.class)
                .hasMessageContaining("Cannot resume fitness goal: goal status is no longer PAUSED or was concurrently modified");

        verify(jdbcTemplate, never()).update(
                org.mockito.ArgumentMatchers.contains("fitness_goal_status_history"),
                any(), any(), any(), any()
        );
    }
}
