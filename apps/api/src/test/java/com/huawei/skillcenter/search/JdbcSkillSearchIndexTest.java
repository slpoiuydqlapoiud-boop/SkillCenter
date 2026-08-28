package com.huawei.skillcenter.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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

class JdbcSkillSearchIndexTest {
    @Test
    void rebuildPersistsOneAtomicSharedProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcSkillSearchIndex index = new JdbcSkillSearchIndex(jdbc, new ObjectMapper().findAndRegisterModules(),
                transactionManager());

        SkillSearchRebuildResult result = index.rebuild(List.of(document("skill-a")), "source-hash");

        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.documentCount()).isEqualTo(1);
        assertThat(result.sourceHash()).isEqualTo("source-hash");
        assertThat(index.backend()).isEqualTo("postgresql");
    }

    @Test
    void duplicateDocumentsAreRejectedBeforePersistence() {
        JdbcSkillSearchIndex index = new JdbcSkillSearchIndex(mock(JdbcTemplate.class),
                new ObjectMapper().findAndRegisterModules(), transactionManager());

        assertThatThrownBy(() -> index.rebuild(List.of(document("skill-a"), document("skill-a")), "source-hash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("documents must not contain duplicate skillId values");
    }

    private SkillSearchDocument document(String skillId) {
        return new SkillSearchDocument(skillId, "Safe skill", "bounded summary", List.of("safe"), "platform",
                "other", "published", "low", Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-27T00:00:00Z"), "1.0.0", "PUBLIC", "");
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any()))
                .thenReturn(new DefaultTransactionStatus(null, false, false, false, false, null));
        return transactions;
    }
}
