package ai.devreport.backend.generation.application;

import ai.devreport.backend.auth.domain.PolicyVersions;
import ai.devreport.backend.generation.domain.GenerationJob;
import ai.devreport.backend.generation.infrastructure.GenerationJobRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ai.devreport.backend.integration.ai.AiHealthResponse;
import ai.devreport.backend.integration.ai.AiProvider;
import ai.devreport.backend.integration.ai.AiServiceClient;
import ai.devreport.backend.integration.ai.AiServiceException;
import ai.devreport.backend.integration.ai.GenerationRequest;
import ai.devreport.backend.integration.ai.GenerationBundle;
import ai.devreport.backend.integration.ai.MockAiServiceClient;
import ai.devreport.backend.report.domain.ReportDocument;
import ai.devreport.backend.usage.application.UsageLimitProperties;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:generation-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long",
	"usage-limits.rate-limit.signup=100"
})
@AutoConfigureMockMvc
class GenerationIntegrationTest {
	@TempDir
	static Path uploadRoot;

	@Autowired
	MockMvc mvc;

	@Autowired
	ControllableAiServiceClient aiService;

	@Autowired
	GenerationJobRepository jobs;

	@Autowired
	GenerationRecovery recovery;

	@Autowired
	UsageLimitProperties usageLimits;

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("storage.upload-path", () -> uploadRoot.toString());
	}

	@AfterEach
	void releaseWorker() {
		aiService.release();
	}

	@Test
	void acceptsRunsAndFailsJobsWhilePreventingDuplicates() throws Exception {
		String token = signupAndLogin("generation-owner@example.com");
		String projectId = createProject(token, "생성 프로젝트");
		String fileId = upload(token, projectId, "notes.txt", "분석 자료");

		aiService.prepare(false);
		String firstJobId = createGeneration(token, projectId, """
			{"fileIds":["%s"],"metadata":{"title":"요청 보고서","author":"김예찬"},
			"instructions":"핵심 내용을 요약해 줘"}
			""".formatted(fileId));
		assertThat(aiService.awaitStarted()).isTrue();

		mvc.perform(post("/api/projects/{projectId}/generations", projectId)
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"fileIds":["%s"],"metadata":{},"instructions":"다시 생성"}
					""".formatted(fileId)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("GENERATION_ALREADY_RUNNING"));
		mvc.perform(delete("/api/projects/{projectId}/files/{fileId}", projectId, fileId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("FILE_IN_USE"));

		aiService.release();
		awaitStatus(token, firstJobId, "COMPLETED");
		assertThat(aiService.bundleRoot()).doesNotExist();
		GenerationJob completed = jobs.findById(UUID.fromString(firstJobId)).orElseThrow();
		mvc.perform(get("/api/generations/{jobId}", firstJobId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.progress").value(100))
			.andExpect(jsonPath("$.currentStage").value("COMPLETED"))
			.andExpect(jsonPath("$.reportId").value(completed.getReportId().toString()));
		assertThat(completed.getReportId()).isNotNull();
		GenerationRequest savedRequest = completed.getRequestDocument();
		assertThat(savedRequest.fileIds()).containsExactly(UUID.fromString(fileId));
		assertThat(savedRequest.metadata()).containsEntry("title", "요청 보고서").containsEntry("author", "김예찬");
		assertThat(savedRequest.instructions()).isEqualTo("핵심 내용을 요약해 줘");
		assertThat(completed.getResultDocument().metadata().title()).isEqualTo("Spring Boot 실습보고서");
		mvc.perform(get("/api/reports/{reportId}", completed.getReportId())
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.document.metadata.title").value("Spring Boot 실습보고서"));

		aiService.prepare(true);
		String failedJobId = createGeneration(token, projectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"실패 테스트"}
			""".formatted(fileId));
		assertThat(aiService.awaitStarted()).isTrue();
		aiService.release();
		awaitStatus(token, failedJobId, "FAILED");
		assertThat(aiService.bundleRoot()).doesNotExist();
		mvc.perform(get("/api/generations/{jobId}", failedJobId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.progress").value(10))
			.andExpect(jsonPath("$.currentStage").value("FAILED"))
			.andExpect(jsonPath("$.failureCode").value("GENERATION_FAILED"));
	}

	@Test
	void rejectsAiReportWithInvalidImageReferenceBeforeSaving() throws Exception {
		String token = signupAndLogin("generation-report-validation@example.com");
		String projectId = createProject(token, "생성 결과 검증 프로젝트");
		String textId = upload(token, projectId, "notes.txt", "분석 자료");

		aiService.prepare(false, imageReport(textId));
		String jobId = createGeneration(token, projectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"이미지 검증"}
			""".formatted(textId));
		assertThat(aiService.awaitStarted()).isTrue();
		aiService.release();
		awaitStatus(token, jobId, "FAILED");

		mvc.perform(get("/api/generations/{jobId}", jobId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.failureCode").value("REPORT_DOCUMENT_INVALID"));
		assertThat(jobs.findById(UUID.fromString(jobId)).orElseThrow().getReportId()).isNull();
	}

	@Test
	void completesGenerationWhenAiOmitsOptionalMetadata() throws Exception {
		String token = signupAndLogin("generation-optional-metadata@example.com");
		String projectId = createProject(token, "선택 메타데이터 프로젝트");
		String fileId = upload(token, projectId, "notes.txt", "분석 자료");
		aiService.prepare(false, reportWithoutOptionalMetadata());

		String jobId = createGeneration(token, projectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"선택 메타데이터 검증"}
			""".formatted(fileId));
		assertThat(aiService.awaitStarted()).isTrue();
		aiService.release();
		awaitStatus(token, jobId, "COMPLETED");

		GenerationJob job = jobs.findById(UUID.fromString(jobId)).orElseThrow();
		mvc.perform(get("/api/reports/{reportId}", job.getReportId())
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.document.metadata.title").value("AI 생성 보고서"))
			.andExpect(jsonPath("$.document.metadata.author").value("AI"))
			.andExpect(jsonPath("$.document.metadata.course").doesNotExist())
			.andExpect(jsonPath("$.document.metadata.date").doesNotExist());
	}

	@Test
	void limitsConcurrentGenerationsAcrossProjectsForOneUser() throws Exception {
		String token = signupAndLogin("generation-concurrency@example.com");
		String firstProjectId = createProject(token, "첫 번째 프로젝트");
		String secondProjectId = createProject(token, "두 번째 프로젝트");
		String thirdProjectId = createProject(token, "세 번째 프로젝트");
		String firstFileId = upload(token, firstProjectId, "first.txt", "첫 번째 자료");
		String secondFileId = upload(token, secondProjectId, "second.txt", "두 번째 자료");
		String thirdFileId = upload(token, thirdProjectId, "third.txt", "세 번째 자료");

		aiService.prepare(false);
		String firstJobId = createGeneration(token, firstProjectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"첫 번째 생성"}
			""".formatted(firstFileId));
		assertThat(aiService.awaitStarted()).isTrue();
		String secondJobId = createGeneration(token, secondProjectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"두 번째 생성"}
			""".formatted(secondFileId));
		awaitActive(secondJobId);
		awaitGenerationCalls(2);
		long jobsBeforeRejectedRequest = jobs.count();
		int generationCallsBeforeRejectedRequest = aiService.generationCalls();

		mvc.perform(post("/api/projects/{projectId}/generations", thirdProjectId)
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"fileIds":["%s"],"metadata":{},"instructions":"세 번째 생성"}
					""".formatted(thirdFileId)))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("GENERATION_CONCURRENCY_LIMIT_EXCEEDED"));
		assertThat(jobs.count()).isEqualTo(jobsBeforeRejectedRequest);
		assertThat(aiService.generationCalls()).isEqualTo(generationCallsBeforeRejectedRequest);

		aiService.release();
		awaitStatus(token, firstJobId, "COMPLETED");
		awaitStatus(token, secondJobId, "COMPLETED");
	}

	@Test
	void requiresRegisteredKeyForNonDefaultModelsAndPassesUserKeyToAiService() throws Exception {
		String token = signupAndLogin("generation-byok@example.com");
		String projectId = createProject(token, "BYOK 프로젝트");
		String fileId = upload(token, projectId, "byok.txt", "BYOK 자료");
		long jobsBefore = jobs.count();

		rejectGeneration(token, projectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"모델만","model":"gemini-3.7-flash"}
			""".formatted(fileId), "GENERATION_REQUEST_INVALID");
		rejectGeneration(token, projectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"없는 모델","provider":"GEMINI","model":"gemini-9"}
			""".formatted(fileId), "AI_MODEL_NOT_ALLOWED");
		rejectGeneration(token, projectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"키 없음","provider":"GEMINI","model":"gemini-3.7-flash"}
			""".formatted(fileId), "AI_CREDENTIAL_REQUIRED");
		assertThat(jobs.count()).isEqualTo(jobsBefore);

		aiService.prepare(false);
		String serverJobId = createGeneration(token, projectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"서버 키"}
			""".formatted(fileId));
		assertThat(aiService.awaitStarted()).isTrue();
		aiService.release();
		awaitStatus(token, serverJobId, "COMPLETED");
		assertThat(aiService.lastProviderApiKey()).isNull();
		assertThat(aiService.lastRequest().provider()).isEqualTo(AiProvider.GEMINI);
		assertThat(aiService.lastRequest().model()).isEqualTo("gemini-3.5-flash-lite");
		assertThat(jobs.findById(UUID.fromString(serverJobId)).orElseThrow().getKeySource())
			.isEqualTo(GenerationJob.KeySource.SERVER);

		String userKey = "AIzaSyUserOwnedKey-0123456789abcdefXYZ";
		mvc.perform(put("/api/me/ai-credentials/GEMINI")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"apiKey\":\"" + userKey + "\"}"))
			.andExpect(status().isOk());

		aiService.prepare(false);
		String userJobId = createGeneration(token, projectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"사용자 키","provider":"GEMINI","model":"gemini-3.7-flash"}
			""".formatted(fileId));
		assertThat(aiService.awaitStarted()).isTrue();
		aiService.release();
		awaitStatus(token, userJobId, "COMPLETED");
		assertThat(aiService.lastProviderApiKey()).isEqualTo(userKey);
		assertThat(aiService.lastRequest().model()).isEqualTo("gemini-3.7-flash");
		String jobBody = mvc.perform(get("/api/generations/{jobId}", userJobId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.provider").value("GEMINI"))
			.andExpect(jsonPath("$.model").value("gemini-3.7-flash"))
			.andReturn().getResponse().getContentAsString();
		assertThat(jobBody).doesNotContain(userKey);
		GenerationJob userJob = jobs.findById(UUID.fromString(userJobId)).orElseThrow();
		assertThat(userJob.getKeySource()).isEqualTo(GenerationJob.KeySource.USER);
		assertThat(userJob.getRequestDocument().toString()).doesNotContain(userKey);
	}

	@Test
	void doesNotBlameUserKeyWhenServerKeyIsRejected() throws Exception {
		String token = signupAndLogin("generation-server-key-rejected@example.com");
		String projectId = createProject(token, "서버 키 거부");
		String fileId = upload(token, projectId, "rejected.txt", "자료");

		aiService.prepare(new AiServiceException(HttpStatus.BAD_REQUEST, "AI_CREDENTIAL_INVALID",
			"등록된 API Key를 provider가 거부했습니다. 키를 확인해 주세요.", null));
		String serverJobId = createGeneration(token, projectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"서버 키"}
			""".formatted(fileId));
		assertThat(aiService.awaitStarted()).isTrue();
		aiService.release();
		awaitStatus(token, serverJobId, "FAILED");
		mvc.perform(get("/api/generations/{jobId}", serverJobId).header("Authorization", bearer(token)))
			.andExpect(jsonPath("$.failureCode").value("AI_SERVICE_UNAVAILABLE"));

		mvc.perform(put("/api/me/ai-credentials/GEMINI")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"apiKey\":\"AIzaSyRejectedLater-0123456789abcdef\"}"))
			.andExpect(status().isOk());
		aiService.prepare(new AiServiceException(HttpStatus.BAD_REQUEST, "AI_CREDENTIAL_INVALID",
			"등록된 API Key를 provider가 거부했습니다. 키를 확인해 주세요.", null));
		String userJobId = createGeneration(token, projectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"사용자 키"}
			""".formatted(fileId));
		assertThat(aiService.awaitStarted()).isTrue();
		aiService.release();
		awaitStatus(token, userJobId, "FAILED");
		mvc.perform(get("/api/generations/{jobId}", userJobId).header("Authorization", bearer(token)))
			.andExpect(jsonPath("$.failureCode").value("AI_CREDENTIAL_INVALID"));
	}

	@Test
	void skipsDailyLimitForUserKeyGenerations() throws Exception {
		long originalLimit = usageLimits.getGeneration().getDailyLimit();
		usageLimits.getGeneration().setDailyLimit(1);
		try {
			String token = signupAndLogin("generation-byok-daily@example.com");
			String projectId = createProject(token, "BYOK 일일 제한");
			String fileId = upload(token, projectId, "daily.txt", "일일 제한 자료");
			aiService.prepare(false);
			String firstJobId = createGeneration(token, projectId, """
				{"fileIds":["%s"],"metadata":{},"instructions":"서버 키 1회"}
				""".formatted(fileId));
			assertThat(aiService.awaitStarted()).isTrue();
			aiService.release();
			awaitStatus(token, firstJobId, "COMPLETED");
			rejectGeneration(token, projectId, """
				{"fileIds":["%s"],"metadata":{},"instructions":"서버 키 2회"}
				""".formatted(fileId), "GENERATION_DAILY_LIMIT_EXCEEDED");

			mvc.perform(put("/api/me/ai-credentials/GEMINI")
					.header("Authorization", bearer(token))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"apiKey\":\"AIzaSyDailyBypassKey-0123456789abcdef\"}"))
				.andExpect(status().isOk());
			aiService.prepare(false);
			String userJobId = createGeneration(token, projectId, """
				{"fileIds":["%s"],"metadata":{},"instructions":"사용자 키는 한도 없음"}
				""".formatted(fileId));
			assertThat(aiService.awaitStarted()).isTrue();
			aiService.release();
			awaitStatus(token, userJobId, "COMPLETED");
			assertThat(aiService.lastProviderApiKey()).isEqualTo("AIzaSyDailyBypassKey-0123456789abcdef");

			mvc.perform(delete("/api/me/ai-credentials/GEMINI").header("Authorization", bearer(token)))
				.andExpect(status().isNoContent());
			rejectGeneration(token, projectId, """
				{"fileIds":["%s"],"metadata":{},"instructions":"다시 서버 키"}
				""".formatted(fileId), "GENERATION_DAILY_LIMIT_EXCEEDED");
		} finally {
			usageLimits.getGeneration().setDailyLimit(originalLimit);
		}
	}

	private void rejectGeneration(String token, String projectId, String requestBody, String expectedCode)
		throws Exception {
		mvc.perform(post("/api/projects/{projectId}/generations", projectId)
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().is4xxClientError())
			.andExpect(jsonPath("$.code").value(expectedCode));
	}

	@Test
	void limitsDailyGenerationsAfterACompletedRequest() throws Exception {
		long originalLimit = usageLimits.getGeneration().getDailyLimit();
		usageLimits.getGeneration().setDailyLimit(1);
		try {
			String token = signupAndLogin("generation-daily@example.com");
			String projectId = createProject(token, "일일 제한 프로젝트");
			String fileId = upload(token, projectId, "daily.txt", "일일 제한 자료");
			aiService.prepare(false);
			String firstJobId = createGeneration(token, projectId, """
				{"fileIds":["%s"],"metadata":{},"instructions":"첫 번째 생성"}
				""".formatted(fileId));
			assertThat(aiService.awaitStarted()).isTrue();
			aiService.release();
			awaitStatus(token, firstJobId, "COMPLETED");
		long jobsBeforeRejectedRequest = jobs.count();
		int generationCallsBeforeRejectedRequest = aiService.generationCalls();

			mvc.perform(post("/api/projects/{projectId}/generations", projectId)
					.header("Authorization", bearer(token))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"fileIds":["%s"],"metadata":{},"instructions":"두 번째 생성"}
						""".formatted(fileId)))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("GENERATION_DAILY_LIMIT_EXCEEDED"));
			assertThat(jobs.count()).isEqualTo(jobsBeforeRejectedRequest);
			assertThat(aiService.generationCalls()).isEqualTo(generationCallsBeforeRejectedRequest);
		} finally {
			usageLimits.getGeneration().setDailyLimit(originalLimit);
		}
	}

	@Test
	void rejectsInvalidMissingAndForeignFiles() throws Exception {
		String token = signupAndLogin("generation-validation@example.com");
		String otherToken = signupAndLogin("generation-validation-other@example.com");
		String projectId = createProject(token, "검증 프로젝트");
		String otherProjectId = createProject(otherToken, "다른 프로젝트");
		String fileId = upload(token, projectId, "valid.txt", "유효 파일");
		String foreignFileId = upload(otherToken, otherProjectId, "foreign.txt", "다른 파일");
		String missingFileId = upload(token, projectId, "missing.txt", "누락 파일");
		Files.delete(uploadRoot.resolve(projectId).resolve(missingFileId));

		for (String body : List.of(
			"{}",
			"{\"fileIds\":[],\"metadata\":{},\"instructions\":\"작성\"}",
			"{\"fileIds\":[\"" + fileId + "\",\"" + fileId
				+ "\"],\"metadata\":{},\"instructions\":\"작성\"}",
			"{\"fileIds\":[\"" + fileId + "\"],\"metadata\":[],\"instructions\":\"작성\"}",
			"{\"fileIds\":[\"" + fileId
				+ "\"],\"metadata\":{},\"instructions\":\"작성\",\"unknown\":true}"
		)) {
			mvc.perform(post("/api/projects/{projectId}/generations", projectId)
					.header("Authorization", bearer(token))
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isBadRequest());
		}

		for (String unavailableId : List.of(foreignFileId, missingFileId, UUID.randomUUID().toString())) {
			mvc.perform(post("/api/projects/{projectId}/generations", projectId)
					.header("Authorization", bearer(token))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"fileIds":["%s"],"metadata":{},"instructions":"작성"}
						""".formatted(unavailableId)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
		}

		mvc.perform(delete("/api/projects/{projectId}/files/{fileId}", projectId, fileId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isNoContent());
		mvc.perform(post("/api/projects/{projectId}/generations", projectId)
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"fileIds":["%s"],"metadata":{},"instructions":"작성"}
					""".formatted(fileId)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
	}

	@Test
	void cancelsRunningJobAndDeletesBundle() throws Exception {
		String token = signupAndLogin("generation-cancel@example.com");
		String otherToken = signupAndLogin("generation-cancel-other@example.com");
		String projectId = createProject(token, "취소 프로젝트");
		String fileId = upload(token, projectId, "cancel.txt", "취소 자료");

		aiService.prepare(false);
		String jobId = createGeneration(token, projectId, """
			{"fileIds":["%s"],"metadata":{},"instructions":"취소 테스트"}
			""".formatted(fileId));
		assertThat(aiService.awaitStarted()).isTrue();
		mvc.perform(delete("/api/generations/{jobId}", jobId)
				.header("Authorization", bearer(otherToken)))
			.andExpect(status().isNotFound());

		mvc.perform(delete("/api/generations/{jobId}", jobId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isNoContent());
		awaitStatus(token, jobId, "CANCELED");
		awaitBundleDeleted();
		GenerationJob canceled = jobs.findById(UUID.fromString(jobId)).orElseThrow();
		assertThat(canceled.getCurrentStage()).isEqualTo(GenerationJob.Stage.CANCELED);
		assertThat(canceled.getCompletedAt()).isNotNull();
	}

	@Test
	void recoversPendingAndInterruptsProcessingJobs() throws Exception {
		String token = signupAndLogin("generation-recovery@example.com");
		UUID pendingProjectId = UUID.fromString(createProject(token, "대기 프로젝트"));
		UUID processingProjectId = UUID.fromString(createProject(token, "실행 프로젝트"));
		UUID pendingFileId = UUID.fromString(upload(token, pendingProjectId.toString(), "pending.txt", "대기 파일"));
		UUID processingFileId = UUID.fromString(upload(token, processingProjectId.toString(),
			"processing.txt", "실행 파일"));
		GenerationJob pending = jobs.save(new GenerationJob(pendingProjectId,
			new GenerationRequest(List.of(pendingFileId), Map.of(), "복구 테스트", AiProvider.GEMINI,
				"gemini-3.5-flash-lite"), GenerationJob.KeySource.SERVER));
		GenerationJob processing = new GenerationJob(processingProjectId,
			new GenerationRequest(List.of(processingFileId), Map.of(), "복구 테스트", AiProvider.GEMINI,
				"gemini-3.5-flash-lite"), GenerationJob.KeySource.SERVER);
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

	private void awaitActive(String jobId) throws InterruptedException {
		for (int attempt = 0; attempt < 100; attempt++) {
			GenerationJob.Status status = jobs.findById(UUID.fromString(jobId))
				.map(GenerationJob::getStatus).orElse(null);
			if (status == GenerationJob.Status.PENDING || status == GenerationJob.Status.PROCESSING) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Generation did not become active");
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

	private String upload(String token, String projectId, String name, String content) throws Exception {
		String body = mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
				.file(new MockMultipartFile("file", name, "text/plain",
					content.getBytes(StandardCharsets.UTF_8)))
				.header("Authorization", bearer(token)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.fileId");
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

	private void awaitBundleDeleted() throws InterruptedException {
		for (int attempt = 0; attempt < 100; attempt++) {
			if (aiService.bundleRoot() != null && Files.notExists(aiService.bundleRoot())) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Generation bundle was not deleted");
	}

	private void awaitGenerationCalls(int expected) throws InterruptedException {
		for (int attempt = 0; attempt < 100; attempt++) {
			if (aiService.generationCalls() >= expected) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("AI generation calls did not reach " + expected);
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
					{"email":"%s","password":"password123","name":"사용자","privacyPolicyVersion":"%s","termsOfServiceVersion":"%s"}
					""".formatted(email, PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE)))
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
		private volatile AiServiceException failure;
		private volatile ReportDocument generatedResult;
		private volatile Path bundleRoot;
		private volatile String lastProviderApiKey;
		private volatile GenerationRequest lastRequest;
		private final AtomicInteger generationCalls = new AtomicInteger();

		void prepare(boolean shouldFail) {
			prepare(shouldFail, null);
		}

		void prepare(boolean shouldFail, ReportDocument result) {
			prepare(shouldFail, result, null);
		}

		void prepare(AiServiceException shouldFailWith) {
			prepare(false, null, shouldFailWith);
		}

		void prepare(boolean shouldFail, ReportDocument result, AiServiceException shouldFailWith) {
			started = new CountDownLatch(1);
			released = new CountDownLatch(1);
			fail = shouldFail;
			failure = shouldFailWith;
			generatedResult = result;
			bundleRoot = null;
			generationCalls.set(0);
		}

		boolean awaitStarted() throws InterruptedException {
			return started.await(2, TimeUnit.SECONDS);
		}

		void release() {
			released.countDown();
		}

		Path bundleRoot() {
			return bundleRoot;
		}

		int generationCalls() {
			return generationCalls.get();
		}

		String lastProviderApiKey() {
			return lastProviderApiKey;
		}

		GenerationRequest lastRequest() {
			return lastRequest;
		}

		@Override
		public AiHealthResponse health() {
			return new AiHealthResponse("UP", "test", "test", "test", true, true);
		}

		@Override
		public void verifyCredential(AiProvider provider, String providerApiKey) {
			if (providerApiKey == null || providerApiKey.startsWith("invalid")) {
				throw new AiServiceException(HttpStatus.BAD_REQUEST, "AI_CREDENTIAL_INVALID", "invalid", null);
			}
		}

		@Override
		public ReportDocument generate(GenerationRequest request, GenerationBundle bundle, String providerApiKey) {
			generationCalls.incrementAndGet();
			lastProviderApiKey = providerApiKey;
			lastRequest = request;
			bundleRoot = bundle.root();
			assertThat(bundleRoot).exists();
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
			if (failure != null) {
				throw failure;
			}
			return generatedResult == null
				? new MockAiServiceClient().generate(request, bundle, providerApiKey) : generatedResult;
		}
	}

	private static ReportDocument imageReport(String fileId) {
		return new ReportDocument(
			new ReportDocument.Metadata("이미지 보고서", null, null, null),
			List.of(new ReportDocument.Section("images", "이미지", List.of(Map.of(
				"id", "screen", "type", "image", "fileId", fileId, "alt", "화면")))));
	}

	private static ReportDocument reportWithoutOptionalMetadata() {
		return new ReportDocument(
			new ReportDocument.Metadata("AI 생성 보고서", "AI", null, null),
			List.of(new ReportDocument.Section("overview", "프로젝트 개요", List.of(Map.of(
				"id", "summary", "type", "paragraph", "content", "분석 결과")))));
	}
}
