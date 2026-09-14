package com.huawei.skillcenter.persistence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Allows the Helm migration hook to run the normal startup migration and then exit.
 * The listener is absent unless the one-shot migration property is explicitly enabled.
 */
@Component
@ConditionalOnProperty(name = "skill-center.migration-only", havingValue = "true")
public final class MigrationOnlyExitListener implements ApplicationListener<ApplicationReadyEvent> {
    private final ConfigurableApplicationContext applicationContext;

    public MigrationOnlyExitListener(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        SpringApplication.exit(applicationContext, () -> 0);
    }
}
