package com.huawei.skillcenter.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationOnlyExitListenerTest {

    @Test
    void closesTheApplicationContextAfterMigrationOnlyStartupIsReady() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            MigrationOnlyExitListener listener = new MigrationOnlyExitListener(context);

            listener.onApplicationEvent(new ApplicationReadyEvent(
                    new SpringApplication(), new String[0], context, Duration.ZERO));

            assertThat(context.isActive()).isFalse();
        }
    }
}
