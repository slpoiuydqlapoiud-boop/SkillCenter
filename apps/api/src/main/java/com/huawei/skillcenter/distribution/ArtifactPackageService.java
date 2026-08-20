package com.huawei.skillcenter.distribution;

import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.skill.SkillCatalogService;
import com.huawei.skillcenter.skill.SkillRecord;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ArtifactPackageService {
    private final SkillCatalogService catalogService;

    public ArtifactPackageService(SkillCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public ArtifactMetadata metadata(String skillId, String version, SkillVersion governedVersion) {
        if (governedVersion != null && hasZipArtifact(governedVersion.artifactPath())) {
            try {
                return new ArtifactMetadata(governedVersion.sha256(), Files.size(Path.of(governedVersion.artifactPath())));
            } catch (java.io.IOException ignored) {
                // Fall through to the generated package when a persisted path is no longer available.
            }
        }
        GeneratedArtifact generated = generate(skillId, version);
        return new ArtifactMetadata(generated.sha256(), generated.sizeBytes());
    }

    public GeneratedArtifact generate(String skillId, String version) {
        SkillRecord skill = catalogService.detail(skillId);
        String markdown = "# " + skill.name() + "\n\n" + (skill.description() == null ? "" : skill.description()) + "\n";
        byte[] bytes;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry("SKILL.md"));
                zip.write(markdown.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            bytes = output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to generate Skill artifact", exception);
        }
        return new GeneratedArtifact(new ByteArrayResource(bytes), sha256(bytes), bytes.length, skillId, version);
    }

    private boolean hasZipArtifact(String artifactPath) {
        return artifactPath != null && !artifactPath.isBlank() && artifactPath.toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash Skill artifact", exception);
        }
    }

    public record ArtifactMetadata(String sha256, long sizeBytes) {
    }

    public record GeneratedArtifact(Resource resource, String sha256, long sizeBytes, String skillId, String version) {
    }
}
