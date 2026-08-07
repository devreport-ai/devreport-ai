package ai.devreport.backend.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ai.devreport.backend.ai.AiHealthResponse;
import ai.devreport.backend.ai.AiServiceClient;
import ai.devreport.backend.ai.GenerationRequest;
import ai.devreport.backend.ai.MockAiServiceClient;
import ai.devreport.backend.report.ReportDocument;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:generation-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class GenerationIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	ControllableAiServiceClient aiService;

	@Autowired
	GenerationJobRepository jobs;

	@Autowired
	GenerationRecovery recovery;

	@AfterEach
	void releaseWorker() {
		aiService.release();
	}

	@Test
	void acceptsRunsAndFailsJobsWhilePreventingDuplicates() throws Exception {
		String token = signupAndLogin("generation-owner@example.com");
		String projectId = createProject(token, "생성 프로젝트");

		aiService.prepare(false);
		String firstJobId = createGeneration(token, projectId, """
			{"document":{"metadata":{"title":"요청 보고서","author":"김예찬","course":"소프트웨어공학",
			"date":"2026-08-07"},"sections":[{"id":"intro","title":"서론",
			"blocks":[{"type":"paragraph","text":"요청 본문"}]}]}}
			""");
		assertThat(aiService.awaitStarted()).isTrue();

		mvc.perform(post("/api/projects/{projectId}/generations", projectId)
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("GENERATION_ALREADY_RUNNING"));

		aiService.release();
		awaitStatus(token, firstJobId, "COMPLETED");
		GenerationJob completed = jobs.findById(UUID.fromString(firstJobId)).orElseThrow();
		mvc.perform(get("/api/generations/{jobId}", firstJobId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.progress").value(100))
			.andExpect(jsonPath("$.currentStage").value("COMPLETED"))
			.andExpect(jsonPath("$.reportId").value(completed.getReportId().toString()));
		assertThat(completed.getReportId()).isNotNull();
		ReportDocument requestDocument = completed.getRequestDocument().document();
		assertThat(requestDocument.metadata()).isEqualTo(
			new ReportDocument.Metadata("요청 보고서", "김예찬", "소프트웨어공학", "2026-08-07"));
		assertThat(requestDocument.sections()).containsExactly(
			new ReportDocument.Section("intro", "서론",
				List.of(Map.of("type", "paragraph", "text", "요청 본문"))));
		assertThat(completed.getResultDocument().metadata().title()).isEqualTo("Spring Boot 실습보고서");
		mvc.perform(get("/api/reports/{reportId}", completed.getReportId())
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.metadata.title").value("Spring Boot 실습보고서"));

		aiService.prepare(true);
		String failedJobId = createGeneration(token, projectId);
		assertThat(aiService.awaitStarted()).isTrue();
		aiService.release();
		awaitStatus(token, failedJobId, "FAILED");
		mvc.perform(get("/api/generations/{jobId}", failedJobId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.progress").value(10))
			.andExpect(jsonPath("$.currentStage").value("FAILED"))
			.andExpect(jsonPath("$.failureCode").value("GENERATION_FAILED"));
	}

	@Test
	void recoversPendingAndInterruptsProcessingJobs() throws Exception {
		String token = signupAndLogin("generation-recovery@example.com");
		UUID pendingProjectId = UUID.fromString(createProject(token, "대기 프로젝트"));
		UUID processingProjectId = UUID.fromString(createProject(token, "실행 프로젝트"));
		GenerationJob pending = jobs.save(new GenerationJob(pendingProjectId, new GenerationRequest(null)));
		GenerationJob processing = new GenerationJob(processingProjectId, new GenerationRequest(null));
		processing.start();
		jobs.save(processing);

		aiService.prepare(false);
		recovery.recover();
		assertThat(aiService.awaitStarted()).isTrue();
		aiService.release();
		awaitRepositoryStatus(pending.getId(), GenerationJob.Status.COMPLETED);
		GenerationJob interrupted = jobs.findById(processing.getId()).orElseThrow();
		assertThat(interrupted.getStatus()).isEqualTo(GenerationJob.Status.FAILED);
		assertThat(interrupted.getFailureCode()).isEqualTo("GENERATION_INTERRUPTED");
	}

	private void awaitRepositoryStatus(UUID jobId, GenerationJob.Status expected) throws InterruptedException {
		for (int attempt = 0; attempt < 100; attempt++) {
			if (jobs.findById(jobId).map(GenerationJob::getStatus).orElse(null) == expected) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Generation did not reach status " + expected);
	}

	private String createGeneration(String token, String projectId) throws Exception {
		return createGeneration(token, projectId, null);
	}

	private String createGeneration(String token, String projectId, String requestBody) throws Exception {
		var request = post("/api/projects/{projectId}/generations", projectId)
			.header("Authorization", bearer(token));
		if (requestBody != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(requestBody);
		}
		String body = mvc.perform(request)
			.andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.jobId");
	}

	private void awaitStatus(String token, String jobId, String expected) throws Exception {
		for (int attempt = 0; attempt < 100; attempt++) {
			String body = mvc.perform(get("/api/generations/{jobId}", jobId)
					.header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			if (expected.equals(JsonPath.read(body, "$.status"))) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Generation did not reach status " + expected);
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

	@TestConfiguration
	static class TestAiConfiguration {

		@Bean
		@Primary
		ControllableAiServiceClient controllableAiServiceClient() {
			return new ControllableAiServiceClient();
		}
	}

	static class ControllableAiServiceClient implements AiServiceClient {

		private volatile CountDownLatch started = new CountDownLatch(1);
		private volatile CountDownLatch released = new CountDownLatch(1);
		private volatile boolean fail;

		void prepare(boolean shouldFail) {
			started = new CountDownLatch(1);
			released = new CountDownLatch(1);
			fail = shouldFail;
		}

		boolean awaitStarted() throws InterruptedException {
			return started.await(2, TimeUnit.SECONDS);
		}

		void release() {
			released.countDown();
		}

		@Override
		public AiHealthResponse health() {
			return new AiHealthResponse("UP", "test", "test", "test", true, true);
		}

		@Override
		public ReportDocument generate(GenerationRequest request) {
			started.countDown();
			try {
				if (!released.await(2, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Test AI release timed out");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
			if (fail) {
				throw new IllegalStateException("AI failed");
			}
			return new MockAiServiceClient().generate(request);
		}
	}
}
