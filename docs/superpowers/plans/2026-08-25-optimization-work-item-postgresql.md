# 优化工作项 PostgreSQL 适配 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为优化工作项提供默认 JSON、显式 PostgreSQL 的持久化端口，支撑持续优化闭环的多实例一致性。

**Architecture:** `OptimizationWorkItemRepository` 隔离领域服务与存储实现。JSON 实现保持现有原子文件语义；JDBC 实现以 JSONB 保存完整对象、受控列支持筛选，并由 PostgreSQL 部分唯一索引保证非终态业务键唯一。Spring 条件选择器确保后端显式配对全局 PostgreSQL，失败不回退。

**Tech Stack:** Java 21, Spring Boot, Spring JDBC, Flyway, PostgreSQL JSONB, JUnit 5, Mockito, AssertJ。

**Spec:** `docs/superpowers/specs/2026-08-25-optimization-work-item-postgresql-design.md`

## Global Constraints

- 默认 `skill-center.optimization-work-item-backend=json`，既有本地 JSON 行为保持兼容。
- PostgreSQL 后端必须同时要求 `skill-center.persistence.backend=postgresql`，不得静默回退 JSON。
- 工作项状态机、证据上下文校验、审计、权限和人工发布/回滚边界不变。
- API、错误响应和敏感字段脱敏边界不变。

---

### Task 1: Persistence port and backend condition

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemRepository.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemBackendCondition.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationWorkItemBackendConfigurationTest.java`

**Interfaces:**
- `OptimizationWorkItemRepository.findAll(String skillId, String status, String ownerId, String sourceVersion)` returns `List<OptimizationWorkItem>`.
- `OptimizationWorkItemRepository.find(String workItemId)` returns `Optional<OptimizationWorkItem>`.
- `OptimizationWorkItemRepository.create(OptimizationWorkItem value)` and `replace(OptimizationWorkItem value)` return the persisted value.
- `OptimizationWorkItemStore` implements the port and remains directly constructible by existing tests.

- [x] Write failing tests for selector normalization, JSON default, and PostgreSQL requiring global PostgreSQL.
- [x] Run the focused configuration test and confirm the missing port/condition behavior fails before implementation.
- [x] Add the repository interface and conditional backend classes; put the JSON condition on `OptimizationWorkItemStore`.
- [x] Change `OptimizationWorkItemService` to depend on the repository port while keeping package constructors accepting the concrete JSON store through interface compatibility.
- [x] Run the focused configuration and existing work-item tests; confirm they pass.

### Task 2: PostgreSQL schema and JDBC repository

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V5__create_optimization_work_items.sql`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/JdbcOptimizationWorkItemStore.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/JdbcOptimizationWorkItemStoreTest.java`

**Interfaces:**
- `JdbcOptimizationWorkItemStore` implements `OptimizationWorkItemRepository` and is active only under `OptimizationWorkItemBackendCondition.Postgresql`.
- JSONB payload is serialized with the injected `ObjectMapper`; every decoded payload is reconstructed through `OptimizationWorkItem`.

- [x] Write failing tests for the SQL schema contract and JDBC conflict/persistence mappings.
- [x] Run the focused JDBC test and verify it fails because the repository/schema is absent.
- [x] Add Flyway V5 with primary key, query indexes, JSONB payload and partial unique active-business-key index.
- [x] Implement transactional `findAll`, `find`, `create` and `replace`; translate duplicate-key and missing-row cases to stable domain errors.
- [x] Run JDBC unit tests and the full work-item service/store suite.

### Task 3: Configuration, error contract and documentation

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`

- [x] Write failing error-contract/configuration tests for PostgreSQL selection without global persistence.
- [x] Run the focused error/configuration tests and confirm the fail-closed expectation.
- [x] Add explicit backend configuration and stable persistence error mapping without exposing internals.
- [x] Document JSON default, PostgreSQL opt-in and remaining real-environment HA/backup acceptance.
- [ ] Run API/Web full verification, lifecycle verifier and `git diff --check`.
