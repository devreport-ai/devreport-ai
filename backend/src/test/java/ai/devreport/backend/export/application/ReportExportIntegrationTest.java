package ai.devreport.backend.export.application;

import ai.devreport.backend.export.domain.ReportExport;
import ai.devreport.backend.export.infrastructure.PdfReportRenderer;
import ai.devreport.backend.export.infrastructure.ReportExportRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import ai.devreport.backend.report.domain.Report;
import ai.devreport.backend.report.application.ReportService;
import ai.devreport.backend.report.infrastructure.ReportRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:report-export-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class ReportExportIntegrationTest {

	@TempDir
	static Path exportRoot;

	@Autowired
	MockMvc mvc;

	@Autowired
	ReportService reportService;

	@Autowired
	ReportExportRepository exports;

	@Autowired
	ReportRepository reportRepository;

	@Autowired
	ReportExportService exportService;

	@MockitoBean
	PdfReportRenderer renderer;

	@Autowired
	ObjectMapper objectMapper;

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("storage.export-path", () -> exportRoot.resolve("pdfs").toString());
		registry.add("storage.upload-path", () -> exportRoot.resolve("uploads").toString());
	}

	@BeforeEach
	void stubRenderer() throws Exception {
		when(renderer.isConfigured()).thenReturn(true);
		when(renderer.path(any(UUID.class))).thenAnswer(invocation -> exportRoot.resolve("pdfs")
			.resolve(invocation.getArgument(0, UUID.class) + ".pdf"));
		doAnswer(invocation -> {
			Path path = renderer.path(invocation.getArgument(0, UUID.class));
			Files.createDirectories(path.getParent());
			Files.writeString(path, "%PDF-test");
			return path;
		}).when(renderer).render(any(UUID.class), anyString());
		when(renderer.delete(any(UUID.class))).thenAnswer(invocation -> {
			try {
				return Files.deleteIfExists(renderer.path(invocation.getArgument(0, UUID.class)));
			} catch (java.io.IOException exception) {
				return false;
			}
		});
	}

	@AfterEach
	void restoreStorageDirectory() throws Exception {
		Path storage = exportRoot.resolve("pdfs");
		if (Files.isRegularFile(storage)) {
			Files.delete(storage);
		}
		Files.createDirectories(storage);
	}

	@Test
	void rejectsExportWithoutSelectedTemplate() throws Exception {
		String token = signupAndLogin("export-template-owner@example.com");
		UUID projectId = createProject(token);
		Report report = reportService.create(projectId, objectMapper.readTree("""
			{"metadata":{"title":"템플릿 없음"},"sections":[]}
			"""));

		mvc.perform(post("/api/reports/{reportId}/exports", report.getId())
				.header("Authorization", bearer(token)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REPORT_TEMPLATE_NOT_SELECTED"));
	}

	@Test
	void rejectsExportWhenPrintUrlIsNotConfigured() throws Exception {
		String token = signupAndLogin("export-url-owner@example.com");
		UUID projectId = createProject(token);
		Report report = reportService.create(projectId, objectMapper.readTree("""
			{"metadata":{"title":"출력 URL 없음"},"sections":[]}
			"""));
		selectTemplate(report);
		when(renderer.isConfigured()).thenReturn(false);

		mvc.perform(post("/api/reports/{reportId}/exports", report.getId())
				.header("Authorization", bearer(token)))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("EXPORT_PRINT_URL_NOT_CONFIGURED"));
	}

	@Test
	void createsDownloadsAndProtectsSampleReportPdf() throws Exception {
		String ownerToken = signupAndLogin("export-owner@example.com");
		String otherToken = signupAndLogin("export-other@example.com");
		UUID projectId = createProject(ownerToken);
		String imageId = upload(ownerToken, projectId, "screen.png", "image/png", png());
		String sample = new ClassPathResource("sample-report.json").getContentAsString(StandardCharsets.UTF_8);
		Report report = reportService.create(projectId, objectMapper.readTree(sample.replace(
			"00000000-0000-4000-8000-000000000001", imageId)));
		selectTemplate(report);

		String response = mvc.perform(post("/api/reports/{reportId}/exports", report.getId())
				.header("Authorization", bearer(ownerToken)))
			.andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString();
		String exportId = JsonPath.read(response, "$.exportId");
		awaitStatus(ownerToken, exportId, "COMPLETED");

		mvc.perform(get("/api/report-exports/{exportId}", exportId)
				.header("Authorization", bearer(otherToken)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND"));
		mvc.perform(get("/api/report-exports/{exportId}/download", exportId)
				.header("Authorization", bearer(otherToken)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND"));
		byte[] pdf = mvc.perform(get("/api/report-exports/{exportId}/download", exportId)
				.header("Authorization", bearer(ownerToken)))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
				"attachment; filename=\"report-" + exportId + ".pdf\""))
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(pdf).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));

		Path storage = exportRoot.resolve("pdfs");
		Files.delete(storage.resolve(exportId + ".pdf"));
		mvc.perform(get("/api/report-exports/{exportId}/download", exportId)
				.header("Authorization", bearer(ownerToken)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("EXPORT_FILE_NOT_FOUND"));

		Files.delete(storage);
		Files.createFile(storage);
		String failedResponse = mvc.perform(post("/api/reports/{reportId}/exports", report.getId())
				.header("Authorization", bearer(ownerToken)))
			.andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString();
		String failedExportId = JsonPath.read(failedResponse, "$.exportId");
		awaitStatus(ownerToken, failedExportId, "FAILED");
		mvc.perform(get("/api/report-exports/{exportId}", failedExportId)
				.header("Authorization", bearer(ownerToken)))
			.andExpect(jsonPath("$.failureCode").value("PDF_GENERATION_FAILED"));
	}

	@Test
	void handlesFailedAndExpiredExports() throws Exception {
		String token = signupAndLogin("export-errors@example.com");
		UUID projectId = createProject(token);
		Report report = reportService.create(projectId, objectMapper.readTree("""
			{"metadata":{"title":"오류 처리"},"sections":[]}
			"""));
		selectTemplate(report);
		ReportExport failed = new ReportExport(report.getId());
		failed.fail("PDF_GENERATION_FAILED", "PDF 생성에 실패했습니다.");
		exports.save(failed);
		ReportExport expired = new ReportExport(report.getId());
		expired.start("expired", Instant.now().plusSeconds(60));
		expired.complete(1, Duration.ofSeconds(-1));
		exports.save(expired);

		mvc.perform(get("/api/report-exports/{exportId}/download", failed.getId())
				.header("Authorization", bearer(token)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EXPORT_FAILED"));
		mvc.perform(get("/api/report-exports/{exportId}", expired.getId())
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("EXPIRED"));
		mvc.perform(get("/api/report-exports/{exportId}/download", expired.getId())
				.header("Authorization", bearer(token)))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("EXPORT_EXPIRED"));
	}

	@Test
	void purgesExpiredAndInterruptedPdfFiles() throws Exception {
		String token = signupAndLogin("export-cleanup@example.com");
		UUID projectId = createProject(token);
		Report report = reportService.create(projectId, objectMapper.readTree("""
			{"metadata":{"title":"정리 대상"},"sections":[]}
			"""));
		selectTemplate(report);
		ReportExport expired = new ReportExport(report.getId());
		expired.start("expired", Instant.now().plusSeconds(60));
		expired.complete(1, Duration.ofSeconds(-1));
		exports.save(expired);
		ReportExport interrupted = new ReportExport(report.getId());
		interrupted.start("interrupted", Instant.now().plusSeconds(60));
		exports.save(interrupted);
		Files.createDirectories(exportRoot.resolve("pdfs"));
		Files.writeString(renderer.path(expired.getId()), "expired");
		Files.writeString(renderer.path(interrupted.getId()), "interrupted");

		exportService.purgeExpired();
		assertThat(renderer.path(expired.getId())).doesNotExist();
		assertThat(exports.findById(expired.getId()).orElseThrow().getStatus())
			.isEqualTo(ReportExport.Status.EXPIRED);

		exportService.recover();
		assertThat(renderer.path(interrupted.getId())).doesNotExist();
		ReportExport recovered = exports.findById(interrupted.getId()).orElseThrow();
		assertThat(recovered.getStatus()).isEqualTo(ReportExport.Status.FAILED);
		assertThat(recovered.getFailureCode()).isEqualTo("EXPORT_INTERRUPTED");
	}

	@Test
	void exposesOnlySnapshotImagesWithAValidRenderToken() throws Exception {
		String token = signupAndLogin("export-render-owner@example.com");
		UUID projectId = createProject(token);
		String imageId = upload(token, projectId, "screen.png", "image/png", png());
		Report report = reportService.create(projectId, objectMapper.readTree("""
			{"metadata":{"title":"출력 데이터"},"sections":[{"id":"section","title":"본문",
			"blocks":[{"id":"image","type":"image","fileId":"%s","alt":"화면"}]}]}
			""".formatted(imageId)));
		selectTemplate(report);
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("reportId", report.getId().toString());
		snapshot.put("projectId", projectId.toString());
		snapshot.put("reportVersion", report.getVersion());
		snapshot.put("document", report.getDocument());
		snapshot.put("templateId", report.getTemplateId());
		snapshot.put("templateVersion", report.getTemplateVersion());
		snapshot.put("presentationSettings", report.getPresentationSettings());
		ReportExport export = exports.save(new ReportExport(report.getId(), snapshot));
		ReportExportService.ExportInput input = exportService.start(export.getId()).orElseThrow();

		mvc.perform(get("/api/report-exports/{exportId}/render-data", export.getId())
				.header("X-Render-Token", input.renderToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reportId").value(report.getId().toString()))
			.andExpect(jsonPath("$.projectId").value(projectId.toString()))
			.andExpect(jsonPath("$.reportVersion").value(0))
			.andExpect(jsonPath("$.templateId").value("modern"))
			.andExpect(jsonPath("$.imageFileIds[0]").value(imageId));

		mvc.perform(get("/api/report-exports/{exportId}/files/{fileId}", export.getId(), imageId)
				.header("X-Render-Token", input.renderToken()))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE));
		mvc.perform(get("/api/report-exports/{exportId}/render-data", export.getId())
				.header("X-Render-Token", "invalid-token"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("EXPORT_RENDER_NOT_FOUND"));

		exportService.complete(export.getId(), 1);
		mvc.perform(get("/api/report-exports/{exportId}/render-data", export.getId())
				.header("X-Render-Token", input.renderToken()))
			.andExpect(status().isNotFound());
	}

	@Test
	void protectsSnapshotImagesUntilExportFinishes() throws Exception {
		String token = signupAndLogin("export-file-owner@example.com");
		UUID projectId = createProject(token);
		String imageId = upload(token, projectId, "screen.png", "image/png", png());
		Report report = reportService.create(projectId, objectMapper.readTree("""
			{"metadata":{"title":"파일 보호"},"sections":[{"id":"section","title":"본문",
			"blocks":[{"id":"image","type":"image","fileId":"%s","alt":"화면"}]}]}
			""".formatted(imageId)));
		selectTemplate(report);
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("reportId", report.getId().toString());
		snapshot.put("projectId", projectId.toString());
		snapshot.put("reportVersion", report.getVersion());
		snapshot.put("document", report.getDocument());
		snapshot.put("templateId", report.getTemplateId());
		snapshot.put("templateVersion", report.getTemplateVersion());
		snapshot.put("presentationSettings", report.getPresentationSettings());
		ReportExport export = exports.saveAndFlush(new ReportExport(report.getId(), snapshot));

		mvc.perform(delete("/api/projects/{projectId}/files/{fileId}", projectId, imageId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("FILE_IN_USE"));

		exportService.start(export.getId()).orElseThrow();
		mvc.perform(delete("/api/projects/{projectId}/files/{fileId}", projectId, imageId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("FILE_IN_USE"));

		exportService.complete(export.getId(), 1);
		mvc.perform(delete("/api/projects/{projectId}/files/{fileId}", projectId, imageId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isNoContent());
	}

	private void selectTemplate(Report report) {
		report.update(report.getDocument(), "modern", 1, Map.of());
		reportRepository.saveAndFlush(report);
	}

	private void awaitStatus(String token, String exportId, String expected) throws Exception {
		for (int attempt = 0; attempt < 150; attempt++) {
			String response = mvc.perform(get("/api/report-exports/{exportId}", exportId)
					.header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			if (expected.equals(JsonPath.read(response, "$.status"))) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Export did not reach status " + expected);
	}

	private UUID createProject(String token) throws Exception {
		String response = mvc.perform(post("/api/projects")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"PDF 프로젝트\"}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(response, "$.projectId"));
	}

	private String upload(String token, UUID projectId, String name, String contentType, byte[] content)
		throws Exception {
		String response = mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
				.file(new MockMultipartFile("file", name, contentType, content))
				.header("Authorization", bearer(token)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.fileId");
	}

	private String signupAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123","name":"사용자"}
					""".formatted(email)))
			.andExpect(status().isCreated());
		String response = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123"}
					""".formatted(email)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.accessToken");
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private static byte[] png() {
		return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
	}
}
