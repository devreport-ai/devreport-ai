package ai.devreport.backend.usage.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

class UsageEventPropertiesTest {

	@Test
	void rejectsRetentionLongerThanNinetyDaysDuringConfigurationBinding() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(
				ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
			.withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues("usage-events.retention=91d")
			.run(context -> assertThat(context).hasFailed());
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(UsageEventProperties.class)
	static class PropertiesConfiguration {
	}
}
