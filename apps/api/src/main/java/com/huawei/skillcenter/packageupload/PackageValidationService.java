package com.huawei.skillcenter.packageupload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class PackageValidationService {
    private static final String DEFAULT_VERSION = "1.0.0";
    private static final Pattern FRONTMATTER_NAME = Pattern.compile("(?m)^name:\\s*(.+?)\\s*$");
    private static final Pattern FRONTMATTER_DESCRIPTION = Pattern.compile("(?m)^description:\\s*(.+?)\\s*$");
    private final ObjectMapper objectMapper;
    private final long maxPackageBytes;
    private final long maxUncompressedBytes;
    private final JsonSchema skillSchema;
    private final PackageSecurityScanCoordinator securityScanCoordinator;

    public PackageValidationService(ObjectMapper objectMapper, long maxPackageBytes, long maxUncompressedBytes) {
        this(objectMapper, maxPackageBytes, maxUncompressedBytes,
                new PackageSecurityScanCoordinator(new PackageSecurityScanService(),
                        new ContractOnlyExternalPackageSecurityScanner(), PackageSecurityExternalMode.DISABLED));
    }

    public PackageValidationService(ObjectMapper objectMapper,
                                    long maxPackageBytes,
                                    long maxUncompressedBytes,
                                    PackageSecurityScanService securityScanService) {
        this(objectMapper, maxPackageBytes, maxUncompressedBytes,
                new PackageSecurityScanCoordinator(securityScanService, new ContractOnlyExternalPackageSecurityScanner(),
                        PackageSecurityExternalMode.DISABLED));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PackageValidationService(ObjectMapper objectMapper,
                                    @org.springframework.beans.factory.annotation.Value("${skill-center.package-max-bytes:20971520}") long maxPackageBytes,
                                    @org.springframework.beans.factory.annotation.Value("${skill-center.package-max-uncompressed-bytes:104857600}") long maxUncompressedBytes,
                                    PackageSecurityScanCoordinator securityScanCoordinator) {
        this.objectMapper = objectMapper;
        this.maxPackageBytes = maxPackageBytes;
        this.maxUncompressedBytes = maxUncompressedBytes;
        this.securityScanCoordinator = securityScanCoordinator == null
                ? new PackageSecurityScanCoordinator(new PackageSecurityScanService(),
                new ContractOnlyExternalPackageSecurityScanner(), PackageSecurityExternalMode.DISABLED)
                : securityScanCoordinator;
        this.skillSchema = loadSkillSchema();
    }

    public PackageValidationResult validate(Path zipPath) {
        List<PackageValidationResult.ValidationError> errors = new ArrayList<>();
        String skillId = null;
        String version = null;
        String riskLevel = "low";
        PackageSecurityScanResult securityScan = new PackageSecurityScanResult("NOT_SCANNED", List.of());
        long sizeBytes = 0;
        if (!Files.isRegularFile(zipPath)) {
            return invalid(errors, "PACKAGE_NOT_FOUND", "file", "上传文件不存在");
        }
        try {
            sizeBytes = Files.size(zipPath);
            if (sizeBytes > maxPackageBytes) {
                errors.add(new PackageValidationResult.ValidationError("PACKAGE_TOO_LARGE", "file", "ZIP 文件超过 20 MiB 限制"));
                return invalid(errors, null, null, sizeBytes);
            }
            String root = null;
            Set<String> names = new TreeSet<>();
            long uncompressedBytes = 0;
            try (ZipFile zip = new ZipFile(zipPath.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName().replace('\\', '/');
                    if (!isSafePath(name)) {
                        errors.add(new PackageValidationResult.ValidationError("UNSAFE_PATH", name, "ZIP 条目包含绝对路径或路径穿越"));
                        continue;
                    }
                    if (name.toLowerCase().endsWith(".zip")) {
                        errors.add(new PackageValidationResult.ValidationError("NESTED_ARCHIVE", name, "不允许嵌套 ZIP 文件"));
                    }
                    String firstSegment = name.substring(0, name.indexOf('/') >= 0 ? name.indexOf('/') : name.length());
                    if (firstSegment.isBlank()) {
                        errors.add(new PackageValidationResult.ValidationError("INVALID_ROOT", name, "ZIP 必须包含安全根目录"));
                    } else if (root == null) {
                        root = firstSegment;
                    } else if (!root.equals(firstSegment)) {
                        errors.add(new PackageValidationResult.ValidationError("MULTIPLE_ROOTS", name, "ZIP 只能包含一个根目录"));
                    }
                    names.add(name);
                    if (!entry.isDirectory()) {
                        try (InputStream input = zip.getInputStream(entry)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = input.read(buffer)) >= 0) {
                                uncompressedBytes += read;
                                if (uncompressedBytes > maxUncompressedBytes) {
                                    errors.add(new PackageValidationResult.ValidationError("UNCOMPRESSED_TOO_LARGE", name, "解压内容超过 100 MiB 限制"));
                                    break;
                                }
                            }
                        }
                    }
                }
                if (root == null) {
                    errors.add(new PackageValidationResult.ValidationError("EMPTY_ARCHIVE", "", "ZIP 不得为空"));
                } else {
                    String skillJsonPath = root + "/skill.json";
                    String skillMdPath = root + "/SKILL.md";
                    skillId = root;
                    version = DEFAULT_VERSION;
                    if (!names.contains(skillMdPath)) {
                        errors.add(new PackageValidationResult.ValidationError("MISSING_FILE", skillMdPath, "缺少 SKILL.md"));
                    }
                    if (names.contains(skillJsonPath)) {
                        JsonNode skillJson = objectMapper.readTree(zip.getInputStream(zip.getEntry(skillJsonPath)));
                        skillId = text(skillJson, "id");
                        version = text(skillJson, "version");
                        riskLevel = riskLevel(skillJson.path("permissions"));
                        Set<ValidationMessage> schemaErrors = skillSchema.validate(skillJson);
                        schemaErrors.stream().sorted(java.util.Comparator.comparing(ValidationMessage::getMessage))
                                .forEach(error -> errors.add(new PackageValidationResult.ValidationError("SCHEMA_INVALID", "skill.json", error.getMessage())));
                        if (skillId == null || !root.equals(skillId)) {
                            errors.add(new PackageValidationResult.ValidationError("ROOT_ID_MISMATCH", "skill.json.id", "根目录名必须等于 skill.json.id"));
                        }
                    }
                    if (names.contains(skillMdPath)) {
                        String markdown = new String(zip.getInputStream(zip.getEntry(skillMdPath)).readAllBytes(), StandardCharsets.UTF_8);
                        if (!markdown.startsWith("---") || !FRONTMATTER_NAME.matcher(markdown).find() || !FRONTMATTER_DESCRIPTION.matcher(markdown).find()) {
                            errors.add(new PackageValidationResult.ValidationError("FRONTMATTER_INVALID", skillMdPath, "SKILL.md 必须包含 name 和 description frontmatter"));
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            errors.add(new PackageValidationResult.ValidationError("ZIP_INVALID", "file", "无法读取或解析 ZIP 文件"));
        }
        securityScan = securityScanCoordinator.scan(zipPath);
        securityScan.findings().forEach(finding -> errors.add(new PackageValidationResult.ValidationError(
                finding.code(), finding.path(), finding.reason())));
        String sha256 = sha256(zipPath, errors);
        return new PackageValidationResult(errors.isEmpty() && "PASSED".equals(securityScan.status()),
                skillId, version, sha256, sizeBytes, List.copyOf(errors), riskLevel,
                securityScan.status(), securityScan.findings(), securityScan.scannerId(), securityScan.scannerVersion());
    }

    private PackageValidationResult invalid(List<PackageValidationResult.ValidationError> errors, String skillId, String version, long sizeBytes) {
        return new PackageValidationResult(false, skillId, version, null, sizeBytes, List.copyOf(errors));
    }

    private PackageValidationResult invalid(List<PackageValidationResult.ValidationError> errors, String code, String path, String reason) {
        errors.add(new PackageValidationResult.ValidationError(code, path, reason));
        return invalid(errors, null, null, 0);
    }

    private boolean isSafePath(String name) {
        if (name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:.*") || name.indexOf('\0') >= 0) {
            return false;
        }
        for (String segment : name.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                return false;
            }
        }
        return true;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String riskLevel(JsonNode permissions) {
        if (permissions == null || !permissions.isObject()) {
            return "low";
        }
        String workspace = permissions.path("workspace").asText("none");
        String network = permissions.path("network").asText("none");
        String shell = permissions.path("shell").asText("none");
        String credentials = permissions.path("credentials").asText("none");
        if ("write".equals(workspace) || "external".equals(network)
                || "unrestricted".equals(shell) || "declared".equals(credentials)) {
            return "high";
        }
        if ("read".equals(workspace) || "internal".equals(network) || "restricted".equals(shell)
                || permissions.path("mcpServers").isArray() && permissions.path("mcpServers").size() > 0) {
            return "medium";
        }
        return "low";
    }

    private String sha256(Path path, List<PackageValidationResult.ValidationError> errors) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                input.transferTo(new java.io.OutputStream() {
                    @Override
                    public void write(int b) {
                        digest.update((byte) b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        digest.update(b, off, len);
                    }
                });
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            errors.add(new PackageValidationResult.ValidationError("HASH_FAILED", "file", "无法计算 ZIP 摘要"));
            return null;
        }
    }

    private JsonSchema loadSkillSchema() {
        try {
            String resourceName = "contracts/schemas/v1/skill.schema.json";
            try (InputStream input = PackageValidationService.class.getClassLoader().getResourceAsStream(resourceName)) {
                if (input == null) {
                    throw new IllegalStateException("Missing classpath resource: " + resourceName);
                }
                return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(input);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载 Skill Schema", exception);
        }
    }
}
