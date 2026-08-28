package com.huawei.skillcenter.quality;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcBenchmarkStoreTest {
    @Test
    void addWritesValidatedBenchmarkAsJsonbPayload() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcBenchmarkStore store = new JdbcBenchmarkStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactionManager());

        BenchmarkResult result = benchmark("benchmark-1", "experiment-1");

        assertThat(store.add(result)).isEqualTo(result);
    }

    @Test
    void duplicateBenchmarkIdIsReportedAsBusinessConflict() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("database detail"));
        JdbcBenchmarkStore store = new JdbcBenchmarkStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactionManager());

        assertThatThrownBy(() -> store.add(benchmark("benchmark-1", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("benchmarkId already exists");
    }

    @Test
    void databaseFailureIsNotCollapsedIntoBenchmarkConflict() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        JdbcBenchmarkStore store = new JdbcBenchmarkStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactionManager());

        assertThatThrownBy(() -> store.add(benchmark("benchmark-1", "")))
                .isInstanceOf(BenchmarkStore.BenchmarkPersistenceException.class);
    }

    private BenchmarkResult benchmark(String id, String experimentId) {
        return new BenchmarkResult(id, "skill-a", "1.0.0", "1.1.0", "24h", "mock",
                "PASSED", Instant.parse("2026-08-25T00:00:00Z"), null,
                "runtime-a", "mcp-a", "llm-a", "suite-a", "suite-v1", experimentId);
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any()))
                .thenReturn(new DefaultTransactionStatus(null, false, false, false, false, null));
        return transactions;
    }
}
