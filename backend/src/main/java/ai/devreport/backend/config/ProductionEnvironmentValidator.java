package ai.devreport.backend.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

public class ProductionEnvironmentValidator implements EnvironmentPostProcessor {

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!environment.acceptsProfiles(Profiles.of("prod"))) {
			return;
		}

		requireConfigured(environment, "DATABASE_PASSWORD");
		requireConfigured(environment, "JWT_SECRET");
		requireConfigured(environment, "AI_INTERNAL_TOKEN");
		requireConfigured(environment, "EXPORT_PRINT_URL");
		requireConfigured(environment, "PUBLIC_APP_URL");
		requireConfigured(environment, "PASSWORD_RESET_FROM");
		requireConfigured(environment, "RESEND_API_KEY");
		if (environment.getProperty("ai.service.mock", Boolean.class, false)) {
			throw new IllegalStateException("운영 환경에서는 AI_SERVICE_MOCK=true를 사용할 수 없습니다.");
		}
		if (!environment.getProperty("security.headers.enabled", Boolean.class, false)) {
			throw new IllegalStateException("운영 환경에서는 보안 헤더가 활성화되어야 합니다.");
		}
		if (!Boolean.FALSE.equals(environment.getProperty("springdoc.api-docs.enabled", Boolean.class))
			|| !Boolean.FALSE.equals(environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class))) {
			throw new IllegalStateException("운영 환경에서는 Swagger/OpenAPI를 비활성화해야 합니다.");
		}
		String actuatorExposure = environment.getProperty("management.endpoints.web.exposure.include", "health");
		if (!"health".equals(actuatorExposure.trim())) {
			throw new IllegalStateException("운영 Actuator는 health endpoint만 노출해야 합니다.");
		}
	}

	private static void requireConfigured(ConfigurableEnvironment environment, String name) {
		String value = environment.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + "이 운영 환경에 설정되지 않았습니다.");
		}
	}
}
