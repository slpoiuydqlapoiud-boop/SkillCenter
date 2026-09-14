package com.huawei.skillcenter.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlPersistenceBackendTest {
    @Test
    void acceptsMysqlJdbcConfigurationForTheDepartmentProfile() {
        MysqlPersistenceProperties properties = new MysqlPersistenceProperties();
        properties.setUrl("jdbc:mysql://127.0.0.1:3306/skillcenter");
        properties.setUsername("skillcenter");
        properties.setPassword("local-only");

        assertThat(MysqlPersistenceConfiguration.hasValidConnectionConfiguration(properties)).isTrue();
    }

    @Test
    void rejectsNonMysqlJdbcConfiguration() {
        MysqlPersistenceProperties properties = new MysqlPersistenceProperties();
        properties.setUrl("jdbc:postgresql://127.0.0.1:5432/skillcenter");
        properties.setUsername("skillcenter");
        properties.setPassword("local-only");

        assertThat(MysqlPersistenceConfiguration.hasValidConnectionConfiguration(properties)).isFalse();
    }
}
