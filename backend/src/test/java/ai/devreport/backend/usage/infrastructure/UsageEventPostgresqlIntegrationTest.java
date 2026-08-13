package ai.devreport.backend.usage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import ai.devreport.backend.auth.application.AuthService;
import ai.devreport.backend.auth.domain.User;
import ai.devreport.backend.project.application.ProjectService;
import ai.devreport.backend.usage.application.UsageEventService;
import ai.devreport.backend.usage.domain.UsageEvent;
import ai.devreport.backend.usage.domain.UsageEventType;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.datasource.url=${TEST_POSTGRES_URL}",
	"spring.datasource.username=${TEST_POSTGRES_USERNAME:devreport}",
	"spring.datasource.password=${TEST_POSTGRES_PASSWORD:devreport}",
	"spring.datasource.driver-class-name=org.postgresql.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long",
	"ai.service.mock=true"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class UsageEventPostgresqlIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	AuthService auth;

	@Autowired
	ProjectService projects;

	@Autowired
	UsageEventService usageEvents;

	@Autowired
	UsageEventRepository events;

	@Autowired
	JdbcTemplate jdbc;

	private UUID userId;

	@AfterEach
	void cleanup() {
		if (userId != null) {
			jdbc.update("DELETE FROM app_users WHERE id = ?", userId);
		}
	}

	@Test
	void projectCreationStoresTimestampAndIgnoresDuplicateEvent() throws Exception {
		String email = "usage-events-postgresql-" + UUID.randomUUID() + "@example.com";
		User user = auth.signup(email, "password123", "PostgreSQL 테스트");
		userId = user.getId();
		String token = auth.login(email, "password123").accessToken();

		String response = mvc.perform(post("/api/projects")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"PostgreSQL 사용 이벤트 프로젝트\"}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		UUID projectId = UUID.fromString(JsonPath.read(response, "$.projectId"));

		UsageEvent event = events.findAll().stream()
			.filter(candidate -> projectId.equals(candidate.getProjectId()))
			.filter(candidate -> candidate.getEventType() == UsageEventType.PROJECT_CREATED)
			.findFirst().orElseThrow();
		OffsetDateTime occurredAt = jdbc.queryForObject(
			"SELECT occurred_at FROM usage_events WHERE id = ?", OffsetDateTime.class, event.getId());
		assertThat(occurredAt).isNotNull();
		assertThat(occurredAt.toInstant()).isEqualTo(event.getOccurredAt());

		usageEvents.projectCreated(userId, projects.get(userId, projectId));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_events WHERE project_id = ?",
			Integer.class, projectId)).isOne();
	}
}
