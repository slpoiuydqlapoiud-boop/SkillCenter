package com.huawei.skillcenter.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.ReviewService;
import com.huawei.skillcenter.packageupload.PackageValidationResult;
import com.huawei.skillcenter.packageupload.StoredPackage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCatalogGovernanceTest {
    @Test
    void onlyApprovedUploadedVersionAppearsInPublicCatalog() throws Exception {
        Path state = Files.createTempDirectory("catalog-governance-").resolve("state.json");
        GovernanceStore store = new GovernanceStore(state, List.of());
        ReviewService reviews = new ReviewService(store);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SkillRepository emptyRepository = new SkillRepository() {
            @Override
            public PageResult<SkillRecord> findPublished(SkillQuery query) {
                return new PageResult<>(List.of(), query.page(), query.pageSize(), 0);
            }

            @Override
            public Optional<SkillRecord> findDetail(String skillId) {
                return Optional.empty();
            }
        };
        SkillCatalogService catalog = new SkillCatalogService(emptyRepository, store, mapper);
        PackageValidationResult result = new PackageValidationResult(true, "demo-skill", "1.0.0", "abc", 42, List.of());
        var review = reviews.submitValidatedPackage(result,
                new StoredPackage("package-1", canonicalExample()),
                new Actor("alice", "maintainer"), "req-1");

        assertEquals(0, catalog.list(new SkillQuery("", "", "", "", 1, 12)).total());
        reviews.approve(review.reviewId(), new Actor("bob", "reviewer"), "req-2");

        PageResult<SkillSummary> published = catalog.list(new SkillQuery("", "", "", "", 1, 12));
        assertEquals(1, published.total());
        assertEquals("demo-skill", published.items().get(0).id());
        assertTrue(catalog.detail("demo-skill").version().equals("1.0.0"));
    }

    @Test
    void readsSkillMdContentForApprovedUploadedVersion() throws Exception {
        Path state = Files.createTempDirectory("catalog-content-").resolve("state.json");
        Path packagePath = Files.createTempFile("skill-content-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(packagePath))) {
            output.putNextEntry(new ZipEntry("demo-skill/SKILL.md"));
            output.write("---\nname: demo-skill\ndescription: Demo skill content\n---\n\n# Demo Skill\n\n真实内容\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        GovernanceStore store = new GovernanceStore(state, List.of());
        ReviewService reviews = new ReviewService(store);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SkillRepository emptyRepository = new SkillRepository() {
            @Override
            public PageResult<SkillRecord> findPublished(SkillQuery query) {
                return new PageResult<>(List.of(), query.page(), query.pageSize(), 0);
            }

            @Override
            public Optional<SkillRecord> findDetail(String skillId) {
                return Optional.empty();
            }
        };
        SkillCatalogService catalog = new SkillCatalogService(emptyRepository, store, mapper);
        PackageValidationResult result = new PackageValidationResult(true, "demo-skill", "1.0.0", "abc", Files.size(packagePath), List.of());
        var review = reviews.submitValidatedPackage(result,
                new StoredPackage("package-content", packagePath.toString()),
                new Actor("alice", "maintainer"), "req-content");
        reviews.approve(review.reviewId(), new Actor("bob", "reviewer"), "req-content-approve");

        assertEquals("---\nname: demo-skill\ndescription: Demo skill content\n---\n\n# Demo Skill\n\n真实内容\n",
                catalog.content("demo-skill"));
    }

    @Test
    void governedCatalogLoadsAllRepositoryPagesBeforeApplyingFilters() throws Exception {
        Path state = Files.createTempDirectory("catalog-pagination-").resolve("state.json");
        GovernanceStore store = new GovernanceStore(state, List.of());
        List<SkillRecord> skills = IntStream.range(0, 51).mapToObj(index -> skill("skill-" + index)).toList();
        AtomicInteger calls = new AtomicInteger();
        SkillRepository repository = new SkillRepository() {
            @Override
            public PageResult<SkillRecord> findPublished(SkillQuery query) {
                calls.incrementAndGet();
                int from = Math.min((query.page() - 1) * 50, skills.size());
                int to = Math.min(from + 50, skills.size());
                return new PageResult<>(skills.subList(from, to), query.page(), query.pageSize(), skills.size());
            }

            @Override
            public Optional<SkillRecord> findDetail(String skillId) {
                return skills.stream().filter(item -> item.id().equals(skillId)).findFirst();
            }
        };
        SkillCatalogService catalog = new SkillCatalogService(repository, store,
                new ObjectMapper().findAndRegisterModules());

        PageResult<SkillSummary> result = catalog.list(new SkillQuery("skill-50", "", "", "", 1, 12));

        assertEquals(1, result.total());
        assertEquals("skill-50", result.items().get(0).id());
        assertEquals(2, calls.get());
    }

    private static SkillRecord skill(String id) {
        return new SkillRecord(id, id, "1.0.0", id, "other", List.of(), "low", "low", "team", "owner",
                "", "blue", "published", "2026-08-18", "2026-08-18", "Java", "Java", "none",
                List.of(), List.of(), List.of(), "", "", "", List.of(), new SkillMetrics(0, 0, 0, 0, 0));
    }

    private static String canonicalExample() {
        java.nio.file.Path workingDirectory = java.nio.file.Path.of(System.getProperty("user.dir"));
        java.nio.file.Path repositoryRoot = java.nio.file.Files.isDirectory(workingDirectory.resolve("examples"))
                ? workingDirectory
                : workingDirectory.getParent().getParent();
        return repositoryRoot.resolve("examples/packages/summarize-release-notes-1.0.0.zip").toString();
    }
}
