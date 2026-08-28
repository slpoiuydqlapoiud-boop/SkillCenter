package com.huawei.skillcenter.relationship;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcSkillRelationStoreTest {
    @Test
    void createWritesValidatedRelation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doNothing().when(jdbc).execute(anyString());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcSkillRelationStore store = new JdbcSkillRelationStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactionManager());

        SkillRelation relation = relation("relation-1");

        assertThat(store.create(relation)).isEqualTo(relation);
    }

    @Test
    void duplicateActiveRelationIsReportedAsBusinessConflict() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doNothing().when(jdbc).execute(anyString());
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("database detail"));
        JdbcSkillRelationStore store = new JdbcSkillRelationStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactionManager());

        assertThatThrownBy(() -> store.create(relation("relation-1")))
                .isInstanceOf(SkillRelationConflictException.class)
                .hasMessage("active relation already exists");
    }

    @Test
    void databaseFailureIsNotCollapsedIntoRelationConflict() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doNothing().when(jdbc).execute(anyString());
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        JdbcSkillRelationStore store = new JdbcSkillRelationStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactionManager());

        assertThatThrownBy(() -> store.create(relation("relation-1")))
                .isInstanceOf(SkillRelationPersistenceException.class);
    }

    private SkillRelation relation(String relationId) {
        return SkillRelation.create(relationId, "skill-a", "1.0.0", "skill-b", "1.0.0",
                SkillRelationType.COMPOSES, "admin", Instant.parse("2026-08-25T00:00:00Z"));
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any()))
                .thenReturn(new DefaultTransactionStatus(null, false, false, false, false, null));
        return transactions;
    }
}
