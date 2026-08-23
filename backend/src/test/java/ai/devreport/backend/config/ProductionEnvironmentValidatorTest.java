package ai.devreport.backend.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionEnvironmentValidatorTest {

	@Test
	void rejectsMissingProductionSecretsBeforeApplicationStartup() {
		assertThatThrownBy(() -> validate(environment().withProperty("DATABASE_PASSWORD", " ")))
			.hasMessageContaining("DATABASE_PASSWORD");
		assertThatThrownBy(() -> validate(environment().withProperty("JWT_SECRET", " ")))
			.hasMessageContaining("JWT_SECRET");
		assertThatThrownBy(() -> validate(environment().withProperty("AI_INTERNAL_TOKEN", " ")))
			.hasMessageContaining("AI_INTERNAL_TOKEN");
		assertThatThrownBy(() -> validate(environment().withProperty("EXPORT_PRINT_URL", " ")))
			.hasMessageContaining("EXPORT_PRINT_URL");
		assertThatThrownBy(() -> validate(environment().withProperty("PUBLIC_APP_URL", " ")))
			.hasMessageContaining("PUBLIC_APP_URL");
		assertThatThrownBy(() -> validate(environment().withProperty("PASSWORD_RESET_FROM", " ")))
			.hasMessageContaining("PASSWORD_RESET_FROM");
		assertThatThrownBy(() -> validate(environment().withProperty("RESEND_API_KEY", " ")))
			.hasMessageContaining("RESEND_API_KEY");
	}

	@Test
	void rejectsMockClientInProduction() {
		assertThatThrownBy(() -> validate(environment().withProperty("ai.service.mock", "true")))
			.hasMessageContaining("AI_SERVICE_MOCK");
	}

	@Test
	void rejectsInsecureProductionOverrides() {
		assertThatThrownBy(() -> validate(environment().withProperty("PUBLIC_APP_URL", "http://app.example.com")))
			.hasMessageContaining("PUBLIC_APP_URL");
		assertThatThrownBy(() -> validate(environment().withProperty("PUBLIC_APP_URL", "https://user@app.example.com")))
			.hasMessageContaining("PUBLIC_APP_URL");
		assertThatThrownBy(() -> validate(environment().withProperty("PUBLIC_APP_URL", "https://app.example.com?q=1")))
			.hasMessageContaining("PUBLIC_APP_URL");
		assertThatThrownBy(() -> validate(environment().withProperty("PUBLIC_APP_URL", "https://app.example.com#reset")))
			.hasMessageContaining("PUBLIC_APP_URL");
		assertThatThrownBy(() -> validate(environment().withProperty("security.headers.enabled", "false")))
			.hasMessageContaining("보안 헤더");
		assertThatThrownBy(() -> validate(environment().withProperty("springdoc.api-docs.enabled", "true")))
			.hasMessageContaining("Swagger/OpenAPI");
		assertThatThrownBy(() -> validate(environment(null, false)))
			.hasMessageContaining("Swagger/OpenAPI");
		assertThatThrownBy(() -> validate(environment(false, null)))
			.hasMessageContaining("Swagger/OpenAPI");
		assertThatThrownBy(() -> validate(
			environment().withProperty("management.endpoints.web.exposure.include", "health,info")))
			.hasMessageContaining("Actuator");
	}

	@Test
	void acceptsConfiguredProductionEnvironment() {
		validate(environment());
	}

	private static MockEnvironment environment() {
		return environment(false, false);
	}

	private static MockEnvironment environment(Boolean apiDocsEnabled, Boolean swaggerUiEnabled) {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("spring.profiles.active", "prod")
			.withProperty("DATABASE_PASSWORD", "database-secret")
			.withProperty("JWT_SECRET", "jwt-secret")
			.withProperty("AI_INTERNAL_TOKEN", "internal-secret")
			.withProperty("EXPORT_PRINT_URL", "https://app.example.com/print/{exportId}")
			.withProperty("PUBLIC_APP_URL", "https://app.example.com")
			.withProperty("PASSWORD_RESET_FROM", "DevReport AI <no-reply@mail.example.com>")
			.withProperty("RESEND_API_KEY", "resend-secret")
			.withProperty("ai.service.mock", "false")
			.withProperty("security.headers.enabled", "true")
			.withProperty("management.endpoints.web.exposure.include", "health");
		if (apiDocsEnabled != null) {
			environment.withProperty("springdoc.api-docs.enabled", apiDocsEnabled.toString());
		}
		if (swaggerUiEnabled != null) {
			environment.withProperty("springdoc.swagger-ui.enabled", swaggerUiEnabled.toString());
		}
		return environment;
	}

	private static void validate(MockEnvironment environment) {
		new ProductionEnvironmentValidator().postProcessEnvironment(environment, null);
	}
}
