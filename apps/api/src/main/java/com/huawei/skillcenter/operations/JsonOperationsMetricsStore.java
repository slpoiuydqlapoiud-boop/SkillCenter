package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonOperationsMetricsStore implements OperationsMetricsStore {
    private static final TypeReference<List<Bucket>> BUCKET_LIST = new TypeReference<>() {
    };

    private final Path path;
    private final ObjectMapper objectMapper;
    private volatile String status = "ENABLED";

    public JsonOperationsMetricsStore(Path path, ObjectMapper objectMapper) {
        this.path = path;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Bucket> load() {
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(path.toFile(), BUCKET_LIST);
        } catch (IOException | RuntimeException exception) {
            status = "DEGRADED";
            return List.of();
        }
    }

    @Override
    public synchronized void save(List<Bucket> buckets) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            objectMapper.writeValue(temporary.toFile(), buckets);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            status = "ENABLED";
        } catch (IOException | RuntimeException exception) {
            status = "DEGRADED";
        }
    }

    @Override
    public synchronized void merge(List<Bucket> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        Map<Long, Bucket> merged = new LinkedHashMap<>();
        load().forEach(bucket -> merged.put(bucket.bucketKey(), bucket));
        deltas.forEach(delta -> merged.merge(delta.bucketKey(), delta, OperationsMetricsStore::combine));
        save(new ArrayList<>(merged.values()));
    }

    @Override
    public String status() {
        return status;
    }
}
