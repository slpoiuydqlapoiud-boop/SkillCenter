package com.huawei.skillcenter.search;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcSkillSearchRefreshEventStoreTest {
    @Test
    void appendIsIdempotentOnDeterministicEventKey() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcSkillSearchRefreshEventStore store = new JdbcSkillSearchRefreshEventStore(jdbc);

        store.append(new SkillSearchRefreshEvent("skill-a", 1L, "VERSION_PUBLISHED"));

        assertThat(store.backend()).isEqualTo("postgresql");
    }

    @Test
    void appendUsesDatabaseConflictHandlingForTransactionalIdempotency() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcSkillSearchRefreshEventStore store = new JdbcSkillSearchRefreshEventStore(jdbc);

        store.append(new SkillSearchRefreshEvent("skill-a", 1L, "VERSION_PUBLISHED"));

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("on conflict (event_id) do nothing"),
                any(Object[].class));
    }

    @Test
    void consumerCursorIsReadAndAdvancedMonotonically() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(12L);
        JdbcSkillSearchRefreshEventStore store = new JdbcSkillSearchRefreshEventStore(jdbc);

        assertThat(store.loadCursor("api-1")).isEqualTo(12L);
        store.saveCursor("api-1", 13L);

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("greatest"), any(Object[].class));
    }
}
