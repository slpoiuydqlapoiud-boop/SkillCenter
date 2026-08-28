package com.huawei.skillcenter.packageupload;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Deterministic local safety gate; external malware/dependency scanners remain separate integrations. */
@Service
public class PackageSecurityScanService {
    private static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(
            ".bin", ".class", ".com", ".dll", ".dylib", ".exe", ".jar", ".msi", ".scr", ".so");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".cfg", ".conf", ".css", ".env", ".html", ".ini", ".java", ".js", ".json", ".md",
            ".py", ".sh", ".sql", ".toml", ".ts", ".txt", ".xml", ".yaml", ".yml");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----");
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{20,}");
    private static final Pattern API_KEY = Pattern.compile("\\bsk-[A-Za-z0-9]{16,}\\b");
    private static final int CARRY_CHARS = 256;

    public PackageSecurityScanResult scan(Path zipPath) {
        if (zipPath == null || !Files.isRegularFile(zipPath)) {
            return localResult("NOT_SCANNED", List.of());
        }
        List<PackageSecurityFinding> findings = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            List<? extends ZipEntry> entries = java.util.Collections.list(zip.entries()).stream()
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : entries) {
                String path = entry.getName().replace('\\', '/');
                String lowerPath = path.toLowerCase(Locale.ROOT);
                if (!entry.isDirectory() && hasExecutableExtension(lowerPath)) {
                    findings.add(new PackageSecurityFinding(
                            "EXECUTABLE_PAYLOAD", path, "HIGH", "包内包含禁止的可执行载荷"));
                }
                if (!entry.isDirectory() && isTextEntry(lowerPath)) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        if (containsSecretPattern(input)) {
                            findings.add(new PackageSecurityFinding(
                                    "SECRET_PATTERN", path, "HIGH", "包内容命中敏感凭据模式"));
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            findings.add(new PackageSecurityFinding(
                    "SECURITY_SCAN_FAILED", "file", "HIGH", "本地安全扫描未完成，请重试"));
            return localResult("NOT_SCANNED", findings);
        }
        return localResult(findings.isEmpty() ? "PASSED" : "BLOCKED", findings);
    }

    private PackageSecurityScanResult localResult(String status, List<PackageSecurityFinding> findings) {
        return new PackageSecurityScanResult(status, "local-package-security", "1", findings);
    }

    private boolean hasExecutableExtension(String path) {
        return EXECUTABLE_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    private boolean isTextEntry(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(path.substring(dot));
    }

    private boolean containsSecretPattern(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        String carry = "";
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            String chunk = carry + new String(buffer, 0, read, StandardCharsets.UTF_8);
            if (PRIVATE_KEY.matcher(chunk).find()
                    || AWS_ACCESS_KEY.matcher(chunk).find()
                    || BEARER_TOKEN.matcher(chunk).find()
                    || API_KEY.matcher(chunk).find()) {
                return true;
            }
            carry = chunk.length() <= CARRY_CHARS
                    ? chunk : chunk.substring(chunk.length() - CARRY_CHARS);
        }
        return false;
    }
}
