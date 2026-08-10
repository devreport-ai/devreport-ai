package ai.devreport.backend.export.application;

import ai.devreport.backend.export.domain.ReportExport;
import ai.devreport.backend.export.infrastructure.PdfReportRenderer;
import ai.devreport.backend.export.infrastructure.ReportExportRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import ai.devreport.backend.report.domain.Report;
import ai.devreport.backend.report.application.ReportService;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
	ReportExportService exportService;

	@Autowired
	PdfReportRenderer renderer;

	@Autowired
	ObjectMapper objectMapper;

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("storage.export-path", () -> exportRoot.resolve("pdfs").toString());
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
	void createsDownloadsAndProtectsSampleReportPdf() throws Exception {
		String ownerToken = signupAndLogin("export-owner@example.com");
		String otherToken = signupAndLogin("export-other@example.com");
		UUID projectId = createProject(ownerToken);
		String sample = new ClassPathResource("sample-report.json").getContentAsString(StandardCharsets.UTF_8);
		Report report = reportService.create(projectId, objectMapper.readTree(sample));

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
		try (PDDocument document = Loader.loadPDF(pdf)) {
			assertThat(new PDFTextStripper().getText(document))
				.contains("Spring Boot 실습보고서", "본 실습에서는 REST API를 구현하였다.",
					"그림 1. 애플리케이션 실행 결과")
				.doesNotContain("overview-features");
		}

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
		ReportExport failed = new ReportExport(report.getId());
		failed.fail("PDF_GENERATION_FAILED", "PDF 생성에 실패했습니다.");
		exports.save(failed);
		ReportExport expired = new ReportExport(report.getId());
		expired.start();
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
		ReportExport expired = new ReportExport(report.getId());
		expired.start();
		expired.complete(1, Duration.ofSeconds(-1));
		exports.save(expired);
		ReportExport interrupted = new ReportExport(report.getId());
		interrupted.start();
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
}
