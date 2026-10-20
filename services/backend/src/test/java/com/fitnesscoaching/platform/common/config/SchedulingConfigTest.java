package com.fitnesscoaching.platform.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Scheduling Configuration Isolation Unit Test")
class SchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    @DisplayName("Scheduling is enabled by default when property is missing")
    void enabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    @Test
    @DisplayName("Scheduling is explicitly disabled when app.scheduling.enabled=false")
    void disabledWhenPropertyIsFalse() {
        contextRunner.withPropertyValues("app.scheduling.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SchedulingConfig.class);
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                });
    }
}
