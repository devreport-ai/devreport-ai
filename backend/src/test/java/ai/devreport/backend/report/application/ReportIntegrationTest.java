package ai.devreport.backend.report.application;

import ai.devreport.backend.report.domain.Report;
import ai.devreport.backend.report.domain.ReportException;
import ai.devreport.backend.report.infrastructure.ReportRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.io.TempDir;
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
	@TempDir
	static Path uploadRoot;

	@Autowired
	MockMvc mvc;

	@Autowired
	ReportService reportService;

	@Autowired
	ReportRepository reports;

	@Autowired
	ObjectMapper objectMapper;

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("storage.upload-path", () -> uploadRoot.toString());
	}

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
					+ "\"title\":\"서론\",\"blocks\":[{\"type\":\"paragraph\","
					+ "\"content\":\"본문\"}]}]}"))
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

	@Test
	void rejectsInvalidImageReferencesOnCreateAndUpdate() throws Exception {
		String token = signupAndLogin("report-image-owner@example.com").token();
		String otherToken = signupAndLogin("report-image-other@example.com").token();
		UUID projectId = createProject(token, "이미지 프로젝트");
		UUID otherProjectId = createProject(otherToken, "다른 이미지 프로젝트");
		String imageId = upload(token, projectId.toString(), "screen.png", "image/png", png());
		String textId = upload(token, projectId.toString(), "notes.txt", "text/plain",
			"메모".getBytes(StandardCharsets.UTF_8));
		String foreignImageId = upload(otherToken, otherProjectId.toString(), "foreign.png", "image/png", png());
		String deletedImageId = upload(token, projectId.toString(), "deleted.png", "image/png", png());
		mvc.perform(delete("/api/projects/{projectId}/files/{fileId}", projectId, deletedImageId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isNoContent());
		String missingImageId = upload(token, projectId.toString(), "missing.png", "image/png", png());
		Files.delete(uploadRoot.resolve(projectId.toString()).resolve(missingImageId));

		Report report = reportService.create(projectId, objectMapper.readTree(validDocument("초안")));
		mvc.perform(put("/api/reports/{reportId}", report.getId())
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(imageDocument(imageId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sections[0].blocks[0].fileId").value(imageId));

		for (String invalidFileId : List.of(textId, foreignImageId, deletedImageId, missingImageId,
			UUID.randomUUID().toString())) {
			mvc.perform(put("/api/reports/{reportId}", report.getId())
					.header("Authorization", bearer(token))
					.contentType(MediaType.APPLICATION_JSON)
					.content(imageDocument(invalidFileId)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REPORT_DOCUMENT_INVALID"));
		}
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

	private String upload(String token, String projectId, String name, String contentType, byte[] content)
		throws Exception {
		String body = mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
				.file(new MockMultipartFile("file", name, contentType, content))
				.header("Authorization", bearer(token)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.fileId");
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

	private static String imageDocument(String fileId) {
		return """
			{"metadata":{"title":"이미지 보고서"},"sections":[{"id":"images",
			"title":"이미지","blocks":[{"id":"screen","type":"image","fileId":"%s","alt":"화면"}]}]}
			""".formatted(fileId);
	}

	private static byte[] png() {
		return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	record Credentials(String token) {
	}
}
