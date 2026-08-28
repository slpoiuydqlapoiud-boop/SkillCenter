package com.huawei.skillcenter.packageupload;

import com.huawei.skillcenter.distribution.ArtifactStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalPackageStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void storesContentUnderOpaqueShaAddressAndReturnsVerifiedMetadata() throws Exception {
        Path source = writeZip("SKILL.md", "# demo");
        String sha256 = sha256(source);
        LocalPackageStorage storage = new LocalPackageStorage(tempDir.resolve("packages").toString());

        StoredPackage stored = storage.save(source, "package-1");

        assertThat(stored.packageId()).isEqualTo("package-1");
        assertThat(stored.path()).isEqualTo("local://sha256/" + sha256 + ".zip");
        assertThat(stored.path()).doesNotContain(tempDir.toString());
        ArtifactStorage.ArtifactMetadata metadata = storage.inspect(stored.path(), sha256);
        assertThat(metadata.sha256()).isEqualTo(sha256);
        assertThat(metadata.sizeBytes()).isEqualTo(Files.size(source));
    }

    @Test
    void reusesAnExistingValidDigestObjectWithoutOverwritingIt() throws Exception {
        Path source = writeZip("SKILL.md", "# stable");
        LocalPackageStorage storage = new LocalPackageStorage(tempDir.resolve("packages").toString());

        StoredPackage first = storage.save(source, "first");
        byte[] persisted = storage.open(first.path(), sha256(source)).resource().getInputStream().readAllBytes();
        StoredPackage second = storage.save(source, "second");

        assertThat(second.path()).isEqualTo(first.path());
        assertThat(storage.open(second.path(), sha256(source)).resource().getInputStream().readAllBytes())
                .isEqualTo(persisted);
    }

    @Test
    void refusesToOverwriteACorruptedExistingDigestObject() throws Exception {
        Path source = writeZip("SKILL.md", "# immutable");
        String sha256 = sha256(source);
        Path root = tempDir.resolve("packages");
        Path target = root.resolve("sha256").resolve(sha256 + ".zip");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "corrupted", StandardCharsets.UTF_8);
        LocalPackageStorage storage = new LocalPackageStorage(root.toString());

        assertThatThrownBy(() -> storage.save(source, "package-immutable"))
                .isInstanceOf(IOException.class)
                .hasMessage("artifact storage conflict");
        assertThat(Files.readString(target)).isEqualTo("corrupted");
    }

    @Test
    void readsLegacyAbsoluteReferencesThroughTheSameIntegrityBoundary() throws Exception {
        Path source = writeZip("SKILL.md", "# legacy");
        LocalPackageStorage storage = new LocalPackageStorage(tempDir.resolve("packages").toString());

        ArtifactStorage.ArtifactMetadata metadata = storage.inspect(source.toString(), sha256(source));

        assertThat(metadata.sizeBytes()).isEqualTo(Files.size(source));
    }

    @Test
    void rejectsTraversalInsideOpaqueLocalReferences() {
        LocalPackageStorage storage = new LocalPackageStorage(tempDir.resolve("packages").toString());

        assertThatThrownBy(() -> storage.inspect("local://../outside.zip", "a".repeat(64)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("published artifact was not found");
    }

    private Path writeZip(String entryName, String content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path source = tempDir.resolve("source-" + System.nanoTime() + ".zip");
        Files.write(source, output.toByteArray());
        return source;
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
