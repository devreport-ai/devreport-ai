package ai.devreport.backend.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import ai.devreport.backend.auth.application.AuthService;
import ai.devreport.backend.auth.domain.PolicyVersions;
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
	void rejectsUploadRateLimitBeforeMultipartParsing() throws Exception {
		int originalLimit = properties.getRateLimit().getUpload();
		properties.getRateLimit().setUpload(1);
		try {
			String token = signupAndLogin("upload-rate-owner@example.com");
			String projectId = createProject(token);
			upload(token, projectId, new byte[] {1});

			mvc.perform(multipart("/api/projects/{projectId}/files", projectId)
					.file(new MockMultipartFile("file", "rejected.txt", "text/plain", new byte[] {2, 3}))
					.header("Authorization", bearer(token)))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
				.andExpect(jsonPath("$.details.retryAfterSeconds").isNumber());

			UUID project = UUID.fromString(projectId);
			assertThat(files.findAllByProjectId(project, org.springframework.data.domain.PageRequest.of(0, 10))
				.getContent()).hasSize(1);
		} finally {
			properties.getRateLimit().setUpload(originalLimit);
		}
	}

	@Test
	void rateLimitUsesDatabaseBucketAndReturnsCommonError() throws Exception {
		int originalLimit = properties.getRateLimit().getSignup();
		properties.getRateLimit().setSignup(1);
		String remoteAddress = "test-ip-" + UUID.randomUUID();
		String firstClientIp = "203.0.113.10";
		String secondClientIp = "203.0.113.11";
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

			mvc.perform(post("/api/auth/signup")
					.header("Forwarded", "for=" + firstClientIp)
					.with(request -> {
						request.setRemoteAddr("10.0.0.10");
						return request;
					})
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"rate-limit-first@example.com\",\"password\":\"password123\",\"name\":\"사용자\",\"privacyPolicyVersion\":\""
						+ PolicyVersions.PRIVACY_POLICY + "\",\"termsOfServiceVersion\":\""
						+ PolicyVersions.TERMS_OF_SERVICE + "\"}"))
				.andExpect(status().isCreated());
			mvc.perform(post("/api/auth/signup")
					.header("Forwarded", "for=" + firstClientIp)
					.with(request -> {
						request.setRemoteAddr("10.0.0.10");
						return request;
					})
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"rate-limit-second@example.com\",\"password\":\"password123\",\"name\":\"사용자\",\"privacyPolicyVersion\":\""
						+ PolicyVersions.PRIVACY_POLICY + "\",\"termsOfServiceVersion\":\""
						+ PolicyVersions.TERMS_OF_SERVICE + "\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
				.andExpect(jsonPath("$.details.retryAfterSeconds").isNumber());
			mvc.perform(post("/api/auth/signup")
					.header("Forwarded", "for=" + secondClientIp)
					.with(request -> {
						request.setRemoteAddr("10.0.0.10");
						return request;
					})
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"rate-limit-third@example.com\",\"password\":\"password123\",\"name\":\"사용자\",\"privacyPolicyVersion\":\""
						+ PolicyVersions.PRIVACY_POLICY + "\",\"termsOfServiceVersion\":\""
						+ PolicyVersions.TERMS_OF_SERVICE + "\"}"))
				.andExpect(status().isCreated());
		} finally {
			properties.getRateLimit().setSignup(originalLimit);
		}
	}

	@Test
	void removesExpiredRateLimitBuckets() {
		Duration originalRetention = properties.getRateLimit().getRetention();
		String expiredKey = "cleanup-expired-" + UUID.randomUUID();
		String currentKey = "cleanup-current-" + UUID.randomUUID();
		properties.getRateLimit().setRetention(Duration.ofMinutes(1));
		try {
			jdbc.update("INSERT INTO rate_limit_buckets (bucket_key, window_started_at, request_count) VALUES (?, ?, 1)",
				expiredKey, Instant.now().minus(Duration.ofHours(1)));
			jdbc.update("INSERT INTO rate_limit_buckets (bucket_key, window_started_at, request_count) VALUES (?, ?, 1)",
				currentKey, Instant.now());

			rateLimits.cleanupExpiredBuckets();

			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_buckets WHERE bucket_key = ?",
				Integer.class, expiredKey)).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_buckets WHERE bucket_key = ?",
				Integer.class, currentKey)).isOne();
		} finally {
			properties.getRateLimit().setRetention(originalRetention);
			jdbc.update("DELETE FROM rate_limit_buckets WHERE bucket_key IN (?, ?)", expiredKey, currentKey);
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
		auth.signup(email, "password123", "사용자", PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE);
		return auth.login(email, "password123").accessToken();
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}
}
