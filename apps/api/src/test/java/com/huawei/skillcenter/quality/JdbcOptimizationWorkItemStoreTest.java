package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.Test;
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

class JdbcOptimizationWorkItemStoreTest {
    @Test
    void createWritesValidatedWorkItemAsJsonbPayload() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = transactionManager();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        JdbcOptimizationWorkItemStore store = new JdbcOptimizationWorkItemStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactions);

        OptimizationWorkItem item = workItem("work-1", "OPEN");

        assertThat(store.create(item)).isEqualTo(item);
    }

    @Test
    void duplicateActiveBusinessKeyUsesStableConflictMessage() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = transactionManager();
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("database detail"));
        JdbcOptimizationWorkItemStore store = new JdbcOptimizationWorkItemStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactions);

        assertThatThrownBy(() -> store.create(workItem("work-1", "OPEN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("active work item already exists");
    }

    @Test
    void databaseFailureDoesNotLookLikeBusinessConflict() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = transactionManager();
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        JdbcOptimizationWorkItemStore store = new JdbcOptimizationWorkItemStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactions);

        assertThatThrownBy(() -> store.create(workItem("work-1", "OPEN")))
                .isInstanceOf(OptimizationWorkItemStore.OptimizationWorkItemPersistenceException.class);
    }

    private OptimizationWorkItem workItem(String id, String status) {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        return new OptimizationWorkItem(id, "skill-a", "1.0.0", "runtime-latency",
                "运行 P95 延迟偏高", "LATENCY", "MEDIUM", List.of("p95Ms=1200"),
                "降低运行延迟", "admin", status, "", OptimizationWorkItem.NONE, "", "",
                "production", "runtime-a", "mcp-a", "llm-a", "suite-a", "suite-v1",
                "admin", now, "admin", now);
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any()))
                .thenReturn(new DefaultTransactionStatus(null, false, false, false, false, null));
        return transactions;
    }
}
