package com.huawei.skillcenter.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

class PersistenceMigrationJournal {
    private final Path journalPath;
    private final ObjectMapper objectMapper;

    PersistenceMigrationJournal(Path journalPath, ObjectMapper objectMapper) {
        if (journalPath == null) {
            throw new IllegalArgumentException("journalPath must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.journalPath = journalPath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    Path path() {
        return journalPath;
    }

    OptionalInt currentVersion(String artifactId) {
        return readRecords().stream()
                .filter(record -> "SUCCESS".equals(record.result()))
                .filter(record -> record.artifactId().equals(artifactId))
                .mapToInt(JournalRecord::toVersion)
                .max();
    }

    void appendSuccess(String artifactId, int fromVersion, int toVersion, Instant executedAt, String requestId) {
        List<JournalRecord> records = new ArrayList<>(readRecords());
        records.add(new JournalRecord(artifactId, fromVersion, toVersion, executedAt, "SUCCESS", requestId));
        records.sort(Comparator
                .comparing(JournalRecord::artifactId)
                .thenComparingInt(JournalRecord::fromVersion)
                .thenComparingInt(JournalRecord::toVersion)
                .thenComparing(JournalRecord::executedAt));
        writeRecords(records);
    }

    private List<JournalRecord> readRecords() {
        if (!Files.exists(journalPath)) {
            return List.of();
        }
        try {
            JournalRecord[] loaded = objectMapper.readValue(journalPath.toFile(), JournalRecord[].class);
            return loaded == null ? List.of() : List.of(loaded);
        } catch (IOException exception) {
            throw new PersistenceControlException("PERSISTENCE_ARTIFACT_CORRUPTED");
        }
    }

    private void writeRecords(List<JournalRecord> records) {
        try {
            Path parent = journalPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = journalPath.resolveSibling(journalPath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), records);
            try {
                Files.move(temporary, journalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, journalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new PersistenceControlException("PERSISTENCE_MIGRATION_FAILED");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record JournalRecord(
            String artifactId,
            int fromVersion,
            int toVersion,
            Instant executedAt,
            String result,
            String requestId) {
    }
}
