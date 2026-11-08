package com.fitnesscoaching.platform.modules.media.adapter.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaPersistenceAdapterUnitTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private MediaPersistenceAdapter adapter;

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEDIA_1 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MEDIA_2 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        adapter = new MediaPersistenceAdapter(jdbcTemplate);
    }

    @Test
    @DisplayName("allMediaExistAndOwnedBy: empty media list returns true immediately without querying DB")
    void allMediaExistAndOwnedBy_emptyList_returnsTrue() {
        assertThat(adapter.allMediaExistAndOwnedBy(List.of(), OWNER_ID)).isTrue();
        assertThat(adapter.allMediaExistAndOwnedBy(null, OWNER_ID)).isTrue();
    }

    @Test
    @DisplayName("allMediaExistAndOwnedBy: checks existence and ownership without requiring CLEAN scan status")
    void allMediaExistAndOwnedBy_verifiesSqlDoesNotEnforceCleanStatus() {
        when(jdbcTemplate.queryForObject(any(String.class), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(2);

        boolean result = adapter.allMediaExistAndOwnedBy(List.of(MEDIA_1, MEDIA_2), OWNER_ID);

        assertThat(result).isTrue();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), any(SqlParameterSource.class), eq(Integer.class));
        assertThat(sqlCaptor.getValue())
                .contains("owner_user_id = :ownerUserId")
                .doesNotContain("scan_status = 'CLEAN'");
    }

    @Test
    @DisplayName("allMediaExistOwnedAndClean: strictly enforces scan_status = 'CLEAN' in query")
    void allMediaExistOwnedAndClean_verifiesSqlEnforcesCleanStatus() {
        when(jdbcTemplate.queryForObject(any(String.class), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(2);

        boolean result = adapter.allMediaExistOwnedAndClean(List.of(MEDIA_1, MEDIA_2), OWNER_ID);

        assertThat(result).isTrue();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), any(SqlParameterSource.class), eq(Integer.class));
        assertThat(sqlCaptor.getValue())
                .contains("owner_user_id = :ownerUserId")
                .contains("scan_status = 'CLEAN'");
    }

    @Test
    @DisplayName("allMediaExistOwnedAndClean: returns false when count returned does not match distinct IDs")
    void allMediaExistOwnedAndClean_countMismatch_returnsFalse() {
        when(jdbcTemplate.queryForObject(any(String.class), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(1);

        boolean result = adapter.allMediaExistOwnedAndClean(List.of(MEDIA_1, MEDIA_2), OWNER_ID);

        assertThat(result).isFalse();
    }
}
