package com.huawei.skillcenter.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcSkillScopeStoreTest {
    @Test
    void createWritesValidatedScope() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcSkillScopeStore store = new JdbcSkillScopeStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactionManager());

        SkillScope scope = scope("skill-a");

        assertThat(store.create(scope)).isEqualTo(scope);
    }

    @Test
    void duplicateSkillIdIsReportedAsBusinessConflict() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("database detail"));
        JdbcSkillScopeStore store = new JdbcSkillScopeStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactionManager());

        assertThatThrownBy(() -> store.create(scope("skill-a")))
                .isInstanceOf(SkillScopeConflictException.class)
                .hasMessage("skillId already exists");
    }

    @Test
    void databaseFailureIsNotCollapsedIntoScopeConflict() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        JdbcSkillScopeStore store = new JdbcSkillScopeStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactionManager());

        assertThatThrownBy(() -> store.create(scope("skill-a")))
                .isInstanceOf(SkillScopePersistenceException.class)
                .hasMessage("Skill scope state is unavailable");
    }

    private SkillScope scope(String skillId) {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        return new SkillScope(skillId, SkillVisibility.PUBLIC, "", List.of(), 1,
                "admin", now, "admin", now);
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any()))
                .thenReturn(new DefaultTransactionStatus(null, false, false, false, false, null));
        return transactions;
    }
}
