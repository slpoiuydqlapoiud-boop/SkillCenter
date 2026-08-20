package com.huawei.skillcenter.packageupload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class LocalPackageStorage {
    private final Path baseDirectory;

    public LocalPackageStorage(@Value("${skill-center.package-storage:./data/packages}") String baseDirectory) {
        this.baseDirectory = Path.of(baseDirectory).toAbsolutePath().normalize();
    }

    public StoredPackage save(MultipartFile file, String packageId) throws IOException {
        Files.createDirectories(baseDirectory);
        String safeId = packageId == null || packageId.isBlank() ? UUID.randomUUID().toString() : packageId;
        Path target = baseDirectory.resolve(safeId + ".zip").normalize();
        if (!target.getParent().equals(baseDirectory)) {
            throw new IOException("invalid package id");
        }
        file.transferTo(target);
        return new StoredPackage(safeId, target.toString());
    }

    public StoredPackage save(Path source, String packageId) throws IOException {
        Files.createDirectories(baseDirectory);
        Path target = baseDirectory.resolve(packageId + ".zip").normalize();
        if (!target.getParent().equals(baseDirectory)) {
            throw new IOException("invalid package id");
        }
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new StoredPackage(packageId, target.toString());
    }
}
