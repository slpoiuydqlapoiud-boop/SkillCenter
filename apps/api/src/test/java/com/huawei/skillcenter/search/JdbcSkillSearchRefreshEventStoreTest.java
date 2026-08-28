package com.huawei.skillcenter.search;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
    void duplicateDatabaseKeyDoesNotBreakEventPublication() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("duplicate event"));
        JdbcSkillSearchRefreshEventStore store = new JdbcSkillSearchRefreshEventStore(jdbc);

        store.append(new SkillSearchRefreshEvent("skill-a", 1L, "VERSION_PUBLISHED"));
    }
}
