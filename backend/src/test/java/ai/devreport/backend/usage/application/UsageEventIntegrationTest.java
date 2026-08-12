package ai.devreport.backend.usage.application;

import ai.devreport.backend.generation.domain.GenerationJob;
import ai.devreport.backend.generation.infrastructure.GenerationJobRepository;
import ai.devreport.backend.integration.ai.AiHealthResponse;
import ai.devreport.backend.integration.ai.AiServiceClient;
import ai.devreport.backend.integration.ai.GenerationBundle;
import ai.devreport.backend.integration.ai.GenerationRequest;
import ai.devreport.backend.integration.ai.MockAiServiceClient;
import ai.devreport.backend.project.application.ProjectService;
import ai.devreport.backend.project.domain.Project;
import ai.devreport.backend.upload.application.ProjectTrashService;
import ai.devreport.backend.usage.domain.UsageEvent;
import ai.devreport.backend.usage.domain.UsageEventType;
import ai.devreport.backend.usage.infrastructure.UsageEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.persistence.EntityManager;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:usage-events-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long",
	"ai.service.mock=false"
})
@AutoConfigureMockMvc
class UsageEventIntegrationTest {

	@TempDir
	static Path storageRoot;

	@Autowired
	MockMvc mvc;

	@Autowired
	UsageEventRepository events;

	@Autowired
	GenerationJobRepository jobs;

	@Autowired
	UsageEventService usageEvents;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EntityManager entityManager;

	@Autowired
	ProjectService projects;

	@Autowired
	ProjectTrashService trash;

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("storage.upload-path", () -> storageRoot.resolve("uploads").toString());
		registry.add("storage.export-path", () -> storageRoot.resolve("exports").toString());
	}

	@Test
	void recordsLifecycleEventsWithoutSensitiveMetadata() throws Exception {
		String token = signupAndLogin("usage-events-owner@example.com");
		String projectId = createProject(token, "사용 이벤트 프로젝트");
		String fileId = upload(token, projectId);

		String jobId = createGeneration(token, projectId, fileId, "개인정보가 포함된 프롬프트");
		awaitStatus(jobId, GenerationJob.Status.COMPLETED);
		GenerationJob completed = jobs.findById(UUID.fromString(jobId)).orElseThrow();
		String reportId = completed.getReportId().toString();

		mvc.perform(put("/api/reports/{reportId}", reportId)
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"document":{"metadata":{"title":"민감한 제목"},"sections":[]},
					"templateId":null,"templateVersion":null,"presentationSettings":{},"expectedVersion":0}
					"""))
			.andExpect(status().isOk());

		String exportBody = mvc.perform(post("/api/reports/{reportId}/exports", reportId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString();
		String exportId = JsonPath.read(exportBody, "$.exportId");
		awaitExport(token, exportId);

		failingAi.set(true);
		String failedJobId = createGeneration(token, projectId, fileId, "두 번째 프롬프트");
		awaitStatus(failedJobId, GenerationJob.Status.FAILED);

		UUID projectUuid = UUID.fromString(projectId);
		List<UsageEvent> projectEvents = events.findAll().stream()
			.filter(event -> projectUuid.equals(event.getProjectId()))
			.toList();
		assertThat(projectEvents).extracting(UsageEvent::getEventType)
			.containsExactlyInAnyOrder(
				UsageEventType.PROJECT_CREATED,
				UsageEventType.FILE_UPLOADED,
				UsageEventType.GENERATION_REQUESTED,
				UsageEventType.GENERATION_COMPLETED,
				UsageEventType.REPORT_EDITED,
				UsageEventType.PDF_EXPORTED,
				UsageEventType.GENERATION_REQUESTED,
				UsageEventType.GENERATION_FAILED);
		assertThat(projectEvents).allSatisfy(event -> {
			assertThat(event.getUserId()).isNotNull();
			assertThat(event.getOccurredAt()).isNotNull();
			assertThat(event.getMetadata().keySet()).isSubsetOf(
				Set.of("contentType", "sizeBytes", "fileCount", "failureCode", "previousVersion"));
			assertThat(event.getMetadata().toString())
				.doesNotContain("개인정보", "프롬프트", "notes.txt", "민감한 제목");
		});
		assertThatThrownBy(() -> projectEvents.get(0).getMetadata().put("unexpected", "value"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(eventsOf(projectEvents, UsageEventType.FILE_UPLOADED))
			.extracting(UsageEvent::getFileId)
			.containsExactly(UUID.fromString(fileId));
		assertThat(eventsOf(projectEvents, UsageEventType.GENERATION_REQUESTED))
			.extracting(UsageEvent::getJobId)
			.containsExactlyInAnyOrder(UUID.fromString(jobId), UUID.fromString(failedJobId));
		assertThat(eventsOf(projectEvents, UsageEventType.GENERATION_COMPLETED))
			.extracting(UsageEvent::getReportId)
			.containsExactly(UUID.fromString(reportId));
		assertThat(eventsOf(projectEvents, UsageEventType.GENERATION_FAILED))
			.extracting(UsageEvent::getJobId)
			.containsExactly(UUID.fromString(failedJobId));
		assertThat(eventsOf(projectEvents, UsageEventType.REPORT_EDITED))
			.extracting(UsageEvent::getReportId)
			.containsExactly(UUID.fromString(reportId));
		assertThat(eventsOf(projectEvents, UsageEventType.PDF_EXPORTED))
			.extracting(UsageEvent::getExportId)
			.containsExactly(UUID.fromString(exportId));

		Project project = projects.get(projectEvents.get(0).getUserId(), projectUuid);
		usageEvents.projectCreated(project.getOwnerId(), project);
		assertThat(events.findAll().stream().filter(event -> projectUuid.equals(event.getProjectId())).count())
			.isEqualTo(8);
	}

	@Test
	void enforcesRetentionAndForeignKeyDeletionPolicies() throws Exception {
		failingAi.set(false);
		String token = signupAndLogin("usage-events-deletion@example.com");
		String projectId = createProject(token, "개별 삭제 정책 프로젝트");
		String fileId = upload(token, projectId);
		String jobId = createGeneration(token, projectId, fileId, "삭제 정책 테스트");
		awaitStatus(jobId, GenerationJob.Status.COMPLETED);
		GenerationJob job = jobs.findById(UUID.fromString(jobId)).orElseThrow();
		UUID reportId = job.getReportId();
		String exportBody = mvc.perform(post("/api/reports/{reportId}/exports", reportId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString();
		UUID exportId = UUID.fromString(JsonPath.read(exportBody, "$.exportId"));
		awaitExport(token, exportId.toString());

		UUID projectUuid = UUID.fromString(projectId);
		UsageEvent fileEvent = eventOf(projectEvents(projectUuid), UsageEventType.FILE_UPLOADED);
		UsageEvent completedEvent = eventOf(projectEvents(projectUuid), UsageEventType.GENERATION_COMPLETED);
		UsageEvent exportEvent = eventOf(projectEvents(projectUuid), UsageEventType.PDF_EXPORTED);

		mvc.perform(delete("/api/projects/{projectId}/files/{fileId}", projectId, fileId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isNoContent());
		clearPersistenceContext();
		assertThat(events.findById(fileEvent.getId()).orElseThrow().getFileId()).isNull();

		jdbc.update("DELETE FROM report_exports WHERE id = ?", exportId);
		clearPersistenceContext();
		assertThat(events.findById(exportEvent.getId()).orElseThrow().getExportId()).isNull();

		jdbc.update("DELETE FROM generation_jobs WHERE id = ?", job.getId());
		clearPersistenceContext();
		assertThat(eventsOf(projectEvents(projectUuid), UsageEventType.GENERATION_REQUESTED))
			.allSatisfy(event -> assertThat(event.getJobId()).isNull());
		assertThat(events.findById(completedEvent.getId()).orElseThrow().getJobId()).isNull();

		jdbc.update("DELETE FROM reports WHERE id = ?", reportId);
		clearPersistenceContext();
		assertThat(events.findById(completedEvent.getId()).orElseThrow().getReportId()).isNull();
		assertThat(events.findById(exportEvent.getId()).orElseThrow().getReportId()).isNull();

		String cascadeToken = signupAndLogin("usage-events-user-cascade@example.com");
		String cascadeProjectId = createProject(cascadeToken, "회원 삭제 프로젝트");
		UUID cascadeUserId = jdbc.queryForObject("SELECT id FROM app_users WHERE email = ?", UUID.class,
			"usage-events-user-cascade@example.com");
		assertThat(projectEvents(UUID.fromString(cascadeProjectId))).isNotEmpty();
		jdbc.update("DELETE FROM app_users WHERE id = ?", cascadeUserId);
		clearPersistenceContext();
		assertThat(events.findAll().stream().noneMatch(event -> cascadeUserId.equals(event.getUserId())))
			.isTrue();

		String oldProjectId = createProject(token, "보존 만료 프로젝트");
		UUID oldProjectUuid = UUID.fromString(oldProjectId);
		UsageEvent oldEvent = events.findAll().stream()
			.filter(event -> oldProjectUuid.equals(event.getProjectId()))
			.findFirst().orElseThrow();
		jdbc.update("UPDATE usage_events SET occurred_at = ? WHERE id = ?",
			Instant.now().minus(Duration.ofDays(91)), oldEvent.getId());
		usageEvents.purgeExpired();
		clearPersistenceContext();
		assertThat(events.findById(oldEvent.getId())).isEmpty();

		String deletedProjectId = createProject(token, "삭제 정책 프로젝트");
		UUID deletedProjectUuid = UUID.fromString(deletedProjectId);
		mvc.perform(delete("/api/projects/{projectId}", deletedProjectId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isNoContent());
		jdbc.update("UPDATE projects SET deleted_at = ? WHERE id = ?",
			Instant.now().minus(Duration.ofDays(31)), deletedProjectUuid);
		trash.purgeExpiredProjects();
		clearPersistenceContext();
		assertThat(events.findAll().stream().noneMatch(event -> deletedProjectUuid.equals(event.getProjectId())))
			.isTrue();
	}

	private List<UsageEvent> projectEvents(UUID projectId) {
		return events.findAll().stream()
			.filter(event -> projectId.equals(event.getProjectId()))
			.toList();
	}

	private static List<UsageEvent> eventsOf(List<UsageEvent> events, UsageEventType eventType) {
		return events.stream().filter(event -> event.getEventType() == eventType).toList();
	}

	private static UsageEvent eventOf(List<UsageEvent> events, UsageEventType eventType) {
		return eventsOf(events, eventType).stream().findFirst().orElseThrow();
	}

	private void clearPersistenceContext() {
		entityManager.clear();
	}

	private String createProject(String token, String name) throws Exception {
		String body = mvc.perform(post("/api/projects")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\"}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.projectId");
	}

	private String upload(String token, String projectId) throws Exception {
		String body = mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
				.file(new MockMultipartFile("file", "notes.txt", "text/plain",
					"파일 본문".getBytes(StandardCharsets.UTF_8)))
				.header("Authorization", bearer(token)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.fileId");
	}

	private String createGeneration(String token, String projectId, String fileId, String instructions)
		throws Exception {
		String body = mvc.perform(post("/api/projects/{projectId}/generations", projectId)
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"fileIds":["%s"],"metadata":{"author":"개인정보"},"instructions":"%s"}
					""".formatted(fileId, instructions)))
			.andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.jobId");
	}

	private void awaitStatus(String jobId, GenerationJob.Status expected) throws InterruptedException {
		for (int attempt = 0; attempt < 100; attempt++) {
			if (jobs.findById(UUID.fromString(jobId)).map(GenerationJob::getStatus).orElse(null) == expected) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Generation did not reach status " + expected);
	}

	private void awaitExport(String token, String exportId) throws Exception {
		for (int attempt = 0; attempt < 150; attempt++) {
			String body = mvc.perform(get("/api/report-exports/{exportId}", exportId)
					.header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			if ("COMPLETED".equals(JsonPath.read(body, "$.status"))) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Export did not complete");
	}

	private String signupAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123","name":"사용자"}
					""".formatted(email)))
			.andExpect(status().isCreated());
		String body = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123"}
					""".formatted(email)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	@Autowired
	private ToggleAiService failingAi;

	@TestConfiguration
	static class TestAiConfiguration {

		@Bean
		@Primary
		ToggleAiService failingAi() {
			return new ToggleAiService();
		}
	}

	static class ToggleAiService implements AiServiceClient {

		private final AtomicBoolean fail = new AtomicBoolean();

		void set(boolean value) {
			fail.set(value);
		}

		@Override
		public AiHealthResponse health() {
			return new AiHealthResponse("UP", "test", "test", "test", true, true);
		}

		@Override
		public ai.devreport.backend.report.domain.ReportDocument generate(GenerationRequest request,
			GenerationBundle bundle) {
			if (fail.get()) {
				throw new IllegalStateException("AI failed");
			}
			return new MockAiServiceClient().generate(request, bundle);
		}
	}
}
