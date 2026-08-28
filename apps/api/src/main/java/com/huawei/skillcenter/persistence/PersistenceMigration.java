package com.huawei.skillcenter.persistence;

import java.io.IOException;
import java.nio.file.Path;

public interface PersistenceMigration {
    String artifactId();

    int fromVersion();

    int toVersion();

    void apply(Path artifactPath) throws IOException;
}
