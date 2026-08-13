package ai.devreport.backend.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import ai.devreport.backend.auth.application.AuthService;
import ai.devreport.backend.upload.infrastructure.UploadedFileRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:usage-limits;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long",
	"usage-limits.upload.max-total-bytes=2",
	"usage-limits.upload.max-files=10"
})
@AutoConfigureMockMvc
class UsageLimitIntegrationTest {

	@TempDir
	static Path uploadRoot;

	@Autowired
	MockMvc mvc;

	@Autowired
	UploadedFileRepository files;

	@Autowired
	AuthService auth;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RateLimitService rateLimits;

	@Autowired
	UsageLimitProperties properties;

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("storage.upload-path", () -> uploadRoot.toString());
	}

	@Test
	void rejectsUploadBeforeCreatingMetadataOrTemporaryFile() throws Exception {
		String token = signupAndLogin("quota-owner@example.com");
		String projectId = createProject(token);
		upload(token, projectId, new byte[] {1});

		mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
				.file(new MockMultipartFile("file", "too-large.txt", "text/plain", new byte[] {2, 3}))
				.header("Authorization", bearer(token)))
			.andExpect(status().isContentTooLarge())
			.andExpect(jsonPath("$.code").value("UPLOAD_STORAGE_QUOTA_EXCEEDED"));

		UUID project = UUID.fromString(projectId);
		assertThat(files.findAllByProjectId(project, org.springframework.data.domain.PageRequest.of(0, 10))
			.getContent())
			.hasSize(1);
		try (var paths = Files.list(uploadRoot.resolve(projectId))) {
			assertThat(paths.filter(Files::isRegularFile).count()).isEqualTo(1);
		}
	}

	@Test
	void rateLimitUsesDatabaseBucketAndReturnsCommonError() {
		int originalLimit = properties.getRateLimit().getSignup();
		properties.getRateLimit().setSignup(1);
		String remoteAddress = "test-ip-" + UUID.randomUUID();
		try {
			rateLimits.checkSignup(remoteAddress);

			assertThatThrownBy(() -> rateLimits.checkSignup(remoteAddress))
				.isInstanceOf(ai.devreport.backend.usage.domain.UsageLimitException.class)
				.satisfies(exception -> {
					var limit = (ai.devreport.backend.usage.domain.UsageLimitException) exception;
					assertThat(limit.status().value()).isEqualTo(429);
					assertThat(limit.code()).isEqualTo("RATE_LIMIT_EXCEEDED");
				});
			assertThat(jdbc.queryForObject("SELECT request_count FROM rate_limit_buckets WHERE bucket_key = ?",
				Integer.class, "signup:ip:" + remoteAddress)).isEqualTo(1);
		} finally {
			properties.getRateLimit().setSignup(originalLimit);
		}
	}

	private String createProject(String token) throws Exception {
		String response = mvc.perform(post("/api/projects")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"quota\"}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.projectId");
	}

	private void upload(String token, String projectId, byte[] content) throws Exception {
		mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
				.file(new MockMultipartFile("file", "notes.txt", "text/plain", content))
				.header("Authorization", bearer(token)))
			.andExpect(status().isCreated());
	}

	private String signupAndLogin(String email) {
		auth.signup(email, "password123", "사용자");
		return auth.login(email, "password123").accessToken();
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}
}
