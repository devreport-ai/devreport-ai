package ai.devreport.backend.upload.api;

import ai.devreport.backend.upload.application.ProjectTrashService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:project-file-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class ProjectFileIntegrationTest {

	@TempDir
	static Path uploadRoot;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ProjectTrashService trashService;

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("storage.upload-path", () -> uploadRoot.toString());
	}

	@Test
	void uploadsListsAndDeletesAllowedFiles() throws Exception {
		String token = signupAndLogin("file-owner@example.com");
		String projectId = createProject(token);
		List<TestFile> allowed = List.of(
			new TestFile("assignment.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII)),
			new TestFile("document.docx",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx()),
			new TestFile("notes.txt", "text/plain", "메모".getBytes(StandardCharsets.UTF_8)),
			new TestFile("readme.md", "text/markdown", "# 제목".getBytes(StandardCharsets.UTF_8)),
			new TestFile("source.zip", "application/zip", zip("Main.java", "class Main {}")),
			new TestFile("screen.jpg", "image/jpeg",
				new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}),
			new TestFile("screen.jpeg", "image/jpeg",
				new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}),
			new TestFile("screen.png", "image/png",
				new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})
		);

		String firstFileId = null;
		String zipFileId = null;
		for (TestFile file : allowed) {
			String body = mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
					.file(file.multipartFile())
					.header("Authorization", bearer(token)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
			if (firstFileId == null) {
				firstFileId = JsonPath.read(body, "$.fileId");
			}
			if (file.name().endsWith(".zip")) {
				zipFileId = JsonPath.read(body, "$.fileId");
			}
		}

		mvc.perform(get("/api/projects/{projectId}/files", projectId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(allowed.size()))
			.andExpect(jsonPath("$.items[?(@.originalName == 'assignment.pdf')].contentType")
				.value("application/pdf"))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalElements").value(allowed.size()))
			.andExpect(jsonPath("$.totalPages").value(1));
		mvc.perform(get("/api/projects/{projectId}/files", projectId).param("size", "101")
				.header("Authorization", bearer(token)))
			.andExpect(status().isBadRequest());
		assertThat(storedFileCount(projectId)).isEqualTo(allowed.size());
		assertThat(uploadRoot.resolve(projectId).resolve(zipFileId + ".extracted/Main.java"))
			.hasContent("class Main {}");

		mvc.perform(delete("/api/projects/{projectId}/files/{fileId}", projectId, zipFileId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isNoContent());
		assertThat(uploadRoot.resolve(projectId).resolve(zipFileId + ".extracted")).doesNotExist();

		mvc.perform(delete("/api/projects/{projectId}/files/{fileId}", projectId, firstFileId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isNoContent());
		assertThat(storedFileCount(projectId)).isEqualTo(allowed.size() - 2);
	}

	@Test
	void rejectsDisallowedAndDisguisedFilesWithStandardErrors() throws Exception {
		String token = signupAndLogin("blocked-file@example.com");
		String projectId = createProject(token);
		List<TestFile> blocked = List.of(
			new TestFile("animation.gif", "image/gif", "GIF89a".getBytes(StandardCharsets.US_ASCII)),
			new TestFile("image.webp", "image/webp", "RIFFWEBP".getBytes(StandardCharsets.US_ASCII)),
			new TestFile("fake.pdf", "image/png", "%PDF-1.4".getBytes(StandardCharsets.US_ASCII)),
			new TestFile("fake.png", "image/png", "not an image".getBytes(StandardCharsets.US_ASCII))
		);

		for (TestFile file : blocked) {
			mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
					.file(file.multipartFile())
					.header("Authorization", bearer(token)))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
		}

		mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
				.file(new TestFile("malicious.zip", "application/zip", zip("../outside.java", "malicious"))
					.multipartFile())
				.header("Authorization", bearer(token)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("ZIP_PATH_INVALID"));

		MockMultipartFile tooLarge = new MockMultipartFile("file", "large.zip", "application/zip",
			new byte[20 * 1024 * 1024 + 1]);
		mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
				.file(tooLarge)
				.header("Authorization", bearer(token)))
			.andExpect(status().isContentTooLarge())
			.andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
		assertThat(storedFileCount(projectId)).isZero();
	}

	@Test
	void servesPreviewableFilesInlineAndOtherFilesAsAttachments() throws Exception {
		String token = signupAndLogin("file-content@example.com");
		String projectId = createProject(token);
		byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
		String imageId = upload(token, projectId, new TestFile("화면.png", "image/png", png));
		byte[] text = "보고서 메모".getBytes(StandardCharsets.UTF_8);
		String textId = upload(token, projectId, new TestFile("notes.txt", "text/plain", text));

		var imageResponse = mvc.perform(get("/api/projects/{projectId}/files/{fileId}", projectId, imageId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andReturn().getResponse();
		assertThat(imageResponse.getContentAsByteArray()).containsExactly(png);
		assertThat(imageResponse.getContentType()).isEqualTo("image/png");
		assertThat(imageResponse.getContentLength()).isEqualTo(png.length);
		assertThat(imageResponse.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(imageResponse.getHeader("Content-Disposition"))
			.startsWith("inline;")
			.contains("filename*=UTF-8''");

		var textResponse = mvc.perform(get("/api/projects/{projectId}/files/{fileId}", projectId, textId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andReturn().getResponse();
		assertThat(textResponse.getContentAsByteArray()).containsExactly(text);
		assertThat(textResponse.getContentType()).isEqualTo("text/plain");
		assertThat(textResponse.getHeader("Content-Disposition")).startsWith("attachment;");

		for (TestFile preview : List.of(
			new TestFile("screen.jpg", "image/jpeg",
				new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}),
			new TestFile("document.pdf", "application/pdf",
				"%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII)))) {
			String fileId = upload(token, projectId, preview);
			assertThat(mvc.perform(get("/api/projects/{projectId}/files/{fileId}", projectId, fileId)
					.header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getHeader("Content-Disposition")).startsWith("inline;");
		}
	}

	@Test
	void protectsFilesWithProjectOwnership() throws Exception {
		String ownerToken = signupAndLogin("file-project-owner@example.com");
		String otherToken = signupAndLogin("file-project-other@example.com");
		String projectId = createProject(ownerToken);

		mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
				.file(new TestFile("notes.txt", "text/plain", new byte[] {1}).multipartFile())
				.header("Authorization", bearer(otherToken)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

		String fileId = upload(ownerToken, projectId,
			new TestFile("notes.txt", "text/plain", "owner".getBytes(StandardCharsets.UTF_8)));
		mvc.perform(get("/api/projects/{projectId}/files/{fileId}", projectId, fileId)
				.header("Authorization", bearer(otherToken)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
		mvc.perform(get("/api/projects/{projectId}/files/{fileId}", projectId, UUID.randomUUID())
				.header("Authorization", bearer(ownerToken)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
	}

	@Test
	void listsAndDeletesMetadataWhenStoredFileIsMissing() throws Exception {
		String token = signupAndLogin("missing-file@example.com");
		String projectId = createProject(token);
		String body = mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
				.file(new TestFile("notes.txt", "text/plain", new byte[] {1}).multipartFile())
				.header("Authorization", bearer(token)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		String fileId = JsonPath.read(body, "$.fileId");
		Files.delete(uploadRoot.resolve(projectId).resolve(fileId));
		mvc.perform(get("/api/projects/{projectId}/files/{fileId}", projectId, fileId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("FILE_STORAGE_ERROR"));

		mvc.perform(get("/api/projects/{projectId}/files", projectId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1));
		mvc.perform(delete("/api/projects/{projectId}/files/{fileId}", projectId, fileId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isNoContent());
		mvc.perform(get("/api/projects/{projectId}/files", projectId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void rejectsStoredFileWhenItsSizeDoesNotMatchMetadata() throws Exception {
		String token = signupAndLogin("mismatched-file@example.com");
		String projectId = createProject(token);
		String fileId = upload(token, projectId,
			new TestFile("notes.txt", "text/plain", "original".getBytes(StandardCharsets.UTF_8)));
		Files.writeString(uploadRoot.resolve(projectId).resolve(fileId), "tampered-content");

		mvc.perform(get("/api/projects/{projectId}/files/{fileId}", projectId, fileId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("FILE_STORAGE_ERROR"));
	}

	@Test
	void rejectsStoredFileReplacedWithSymbolicLink() throws Exception {
		String token = signupAndLogin("symbolic-link-file@example.com");
		String projectId = createProject(token);
		String fileId = upload(token, projectId,
			new TestFile("notes.txt", "text/plain", "original".getBytes(StandardCharsets.UTF_8)));
		Path stored = uploadRoot.resolve(projectId).resolve(fileId);
		Path target = Files.writeString(uploadRoot.resolve("link-target.txt"), "outside");
		Files.delete(stored);
		Files.createSymbolicLink(stored, target);

		mvc.perform(get("/api/projects/{projectId}/files/{fileId}", projectId, fileId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("FILE_STORAGE_ERROR"));
	}

	@Test
	void restoresFilesFromTrashAndPurgesThemAfterThirtyDays() throws Exception {
		String token = signupAndLogin("file-trash-owner@example.com");
		String projectId = createProject(token);
		mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
				.file(new TestFile("notes.txt", "text/plain", new byte[] {1}).multipartFile())
				.header("Authorization", bearer(token)))
			.andExpect(status().isCreated());

		mvc.perform(delete("/api/projects/{projectId}", projectId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isNoContent());
		assertThat(storedFileCount(projectId)).isOne();
		mvc.perform(post("/api/projects/{projectId}/restore", projectId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk());
		mvc.perform(get("/api/projects/{projectId}/files", projectId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1));

		mvc.perform(delete("/api/projects/{projectId}", projectId)
				.header("Authorization", bearer(token)))
			.andExpect(status().isNoContent());
		jdbc.update("UPDATE projects SET deleted_at = ? WHERE id = ?",
			Instant.now().minus(Duration.ofDays(31)), UUID.fromString(projectId));
		trashService.purgeExpiredProjects();

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM projects WHERE id = ?", Integer.class,
			UUID.fromString(projectId))).isZero();
		assertThat(Files.exists(uploadRoot.resolve(projectId))).isFalse();
	}

	private String createProject(String token) throws Exception {
		String body = mvc.perform(post("/api/projects")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"파일 프로젝트\"}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.projectId");
	}

	private String upload(String token, String projectId, TestFile file) throws Exception {
		String body = mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
				.file(file.multipartFile())
				.header("Authorization", bearer(token)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.fileId");
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

	private long storedFileCount(String projectId) throws IOException {
		Path directory = uploadRoot.resolve(UUID.fromString(projectId).toString());
		if (Files.notExists(directory)) {
			return 0;
		}
		try (var paths = Files.list(directory)) {
			return paths.filter(Files::isRegularFile).count();
		}
	}

	private static byte[] docx() throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			addZipEntry(zip, "[Content_Types].xml", "<Types/>");
			addZipEntry(zip, "word/document.xml", "<document/>");
		}
		return output.toByteArray();
	}

	private static byte[] zip(String name, String content) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			addZipEntry(zip, name, content);
		}
		return output.toByteArray();
	}

	private static void addZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private record TestFile(String name, String contentType, byte[] content) {
		MockMultipartFile multipartFile() {
			return new MockMultipartFile("file", name, contentType, content);
		}
	}
}
