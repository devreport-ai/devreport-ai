package ai.devreport.backend.report.application;

import ai.devreport.backend.report.domain.Report;
import ai.devreport.backend.report.domain.ReportException;
import ai.devreport.backend.report.infrastructure.ReportRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:report-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class ReportIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	ReportService reportService;

	@Autowired
	ReportRepository reports;

	@Autowired
	ObjectMapper objectMapper;

	@Test
	void readsAndUpdatesOwnedSchemaValidReport() throws Exception {
		Credentials owner = signupAndLogin("report-owner@example.com");
		Credentials other = signupAndLogin("report-other@example.com");
		UUID projectId = createProject(owner.token(), "보고서 프로젝트");
		Report report = reportService.create(projectId, objectMapper.readTree(validDocument("초안")));
		long initialVersion = report.getVersion();
		Instant initialUpdatedAt = report.getUpdatedAt();

		mvc.perform(get("/api/reports/{reportId}", report.getId())
				.header("Authorization", bearer(owner.token())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.metadata.title").value("초안"));
		mvc.perform(get("/api/reports/{reportId}", report.getId())
				.header("Authorization", bearer(other.token())))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
		mvc.perform(put("/api/reports/{reportId}", report.getId())
				.header("Authorization", bearer(other.token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(validDocument("탈취 시도")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));

		mvc.perform(put("/api/reports/{reportId}", report.getId())
				.header("Authorization", bearer(owner.token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(validDocument("수정본")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.metadata.title").value("수정본"));
		Report updated = reports.findById(report.getId()).orElseThrow();
		assertThat(updated.getVersion()).isGreaterThan(initialVersion);
		assertThat(updated.getUpdatedAt()).isAfter(initialUpdatedAt);

		mvc.perform(put("/api/reports/{reportId}", report.getId())
				.header("Authorization", bearer(owner.token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"metadata\":{\"title\":\"오류\"},\"sections\":[{\"id\":\"intro\","
					+ "\"title\":\"서론\",\"blocks\":[{\"id\":\"intro-summary\","
					+ "\"type\":\"paragraph\"}]}]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("REPORT_DOCUMENT_INVALID"));
		assertThat(objectMapper.valueToTree(reports.findById(report.getId()).orElseThrow().getDocument())
			.at("/metadata/title").asString()).isEqualTo("수정본");
		assertThatThrownBy(() -> reportService.create(projectId,
			objectMapper.readTree("{\"metadata\":{},\"sections\":[]}")))
			.isInstanceOf(ReportException.class)
			.hasMessageContaining("JSON Schema");
		assertThat(reports.count()).isEqualTo(1);
	}

	private UUID createProject(String token, String name) throws Exception {
		String body = mvc.perform(post("/api/projects")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\"}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.projectId"));
	}

	private Credentials signupAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123","name":"사용자"}
					""".formatted(email)))
			.andExpect(status().isCreated());
		String loginBody = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123"}
					""".formatted(email)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return new Credentials(JsonPath.read(loginBody, "$.accessToken"));
	}

	private static String validDocument(String title) {
		return """
			{"metadata":{"title":"%s"},"sections":[{"id":"intro",
			"title":"서론","blocks":[{"id":"intro-summary","type":"paragraph","content":"본문"}]}]}
			""".formatted(title);
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	record Credentials(String token) {
	}
}
