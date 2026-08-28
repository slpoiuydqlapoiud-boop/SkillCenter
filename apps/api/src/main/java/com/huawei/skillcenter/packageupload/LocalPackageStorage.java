package com.huawei.skillcenter.packageupload;

import com.huawei.skillcenter.distribution.ArtifactIntegrityVerifier;
import com.huawei.skillcenter.distribution.ArtifactNotFoundException;
import com.huawei.skillcenter.distribution.ArtifactStorage;
import com.huawei.skillcenter.distribution.ArtifactStorageHealth;
import com.huawei.skillcenter.distribution.ArtifactStorageIdentity;
import com.huawei.skillcenter.distribution.ArtifactStorageReadiness;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "skill-center.artifact-storage-backend", havingValue = "local", matchIfMissing = true)
public class LocalPackageStorage implements ArtifactStorage, ArtifactStorageHealth, ArtifactStorageIdentity {
    public static final String IDENTITY = "local-filesystem";
    private static final String LOCAL_SCHEME = "local://";
    private final Path baseDirectory;

    public LocalPackageStorage(@Value("${skill-center.package-storage:./data/packages}") String baseDirectory) {
        this.baseDirectory = Path.of(baseDirectory).toAbsolutePath().normalize();
    }

    public StoredPackage save(MultipartFile file, String packageId) throws IOException {
        Path temporary = Files.createTempFile("skill-package-", ".upload");
        try {
            file.transferTo(temporary);
            ArtifactStorage.StoredArtifact stored = store(temporary, packageId);
            return new StoredPackage(stored.packageId(), stored.reference());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public StoredPackage save(Path source, String packageId) throws IOException {
        ArtifactStorage.StoredArtifact stored = store(source, packageId);
        return new StoredPackage(stored.packageId(), stored.reference());
    }

    @Override
    public String identity() {
        return IDENTITY;
    }

    @Override
    public ArtifactStorage.StoredArtifact store(Path source, String packageId) throws IOException {
        if (source == null || !Files.isRegularFile(source) || Files.isSymbolicLink(source)) {
            throw new IOException("artifact source is invalid");
        }
        String digest = sha256(source);
        long sizeBytes = Files.size(source);
        Path target = baseDirectory.resolve("sha256").resolve(digest + ".zip").normalize();
        if (!target.startsWith(baseDirectory)) {
            throw new IOException("artifact storage reference is invalid");
        }
        Files.createDirectories(target.getParent());
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            ensureExistingTargetIsValid(target, digest);
            return new ArtifactStorage.StoredArtifact(safePackageId(packageId), referenceFor(digest), digest, sizeBytes);
        }

        Path temporary = Files.createTempFile(target.getParent(), digest + ".", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException race) {
                ensureExistingTargetIsValid(target, digest);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                try {
                    Files.move(temporary, target);
                } catch (FileAlreadyExistsException race) {
                    ensureExistingTargetIsValid(target, digest);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new ArtifactStorage.StoredArtifact(safePackageId(packageId), referenceFor(digest), digest, sizeBytes);
    }

    @Override
    public ArtifactStorage.ArtifactMetadata inspect(String reference, String expectedSha256) {
        Path path = resolveReference(reference);
        ArtifactIntegrityVerifier.VerifiedArtifact verified = ArtifactIntegrityVerifier.verify(path, expectedSha256);
        return new ArtifactStorage.ArtifactMetadata(verified.sha256(), verified.sizeBytes());
    }

    @Override
    public ArtifactStorage.ArtifactResource open(String reference, String expectedSha256) {
        Path path = resolveReference(reference);
        ArtifactIntegrityVerifier.VerifiedArtifact verified = ArtifactIntegrityVerifier.verify(path, expectedSha256);
        return new ArtifactStorage.ArtifactResource(new FileSystemResource(path), verified.sha256(), verified.sizeBytes());
    }

    @Override
    public ArtifactStorageReadiness readiness() {
        return new ArtifactStorageReadiness("local", "DEGRADED", "ARTIFACT_STORAGE_LOCAL_ONLY",
                "本地制品存储仅适用于开发或单节点环境");
    }

    private void ensureExistingTargetIsValid(Path target, String digest) throws IOException {
        try {
            ArtifactIntegrityVerifier.verify(target, digest);
        } catch (ArtifactNotFoundException invalid) {
            throw new IOException("artifact storage conflict");
        }
    }

    private Path resolveReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new ArtifactNotFoundException("published artifact was not found");
        }
        if (reference.startsWith(LOCAL_SCHEME)) {
            String relative = reference.substring(LOCAL_SCHEME.length());
            Path resolved = baseDirectory.resolve(relative).normalize();
            if (!resolved.startsWith(baseDirectory) || relative.isBlank() || relative.contains("..")) {
                throw new ArtifactNotFoundException("published artifact was not found");
            }
            return resolved;
        }
        Path legacy;
        try {
            legacy = Path.of(reference);
        } catch (RuntimeException invalid) {
            throw new ArtifactNotFoundException("published artifact was not found");
        }
        if (!legacy.isAbsolute() && containsTraversal(legacy)) {
            throw new ArtifactNotFoundException("published artifact was not found");
        }
        return legacy.toAbsolutePath().normalize();
    }

    private boolean containsTraversal(Path path) {
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private String referenceFor(String digest) {
        return LOCAL_SCHEME + "sha256/" + digest.toLowerCase(Locale.ROOT) + ".zip";
    }

    private String safePackageId(String packageId) {
        return packageId == null || packageId.isBlank() ? UUID.randomUUID().toString() : packageId;
    }

    private String sha256(Path source) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(source)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("artifact hashing unavailable");
        }
    }
}
