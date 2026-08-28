package com.huawei.skillcenter.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "skill-center.persistence.postgresql", ignoreInvalidFields = true)
public class PostgresPersistenceProperties {
    private String url;
    private String username;
    private String password;
    private Duration timeout = Duration.ofSeconds(5);
    private int minimumIdle = 2;
    private int maximumPoolSize = 10;
    private Duration readinessSlo = Duration.ofMillis(250);

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMinimumIdle() {
        return minimumIdle;
    }

    public void setMinimumIdle(int minimumIdle) {
        this.minimumIdle = minimumIdle;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public Duration getReadinessSlo() {
        return readinessSlo;
    }

    public void setReadinessSlo(Duration readinessSlo) {
        this.readinessSlo = readinessSlo;
    }
}
