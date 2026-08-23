package ai.devreport.backend.credential.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.devreport.backend.auth.domain.PolicyVersions;
import ai.devreport.backend.credential.domain.UserAiCredential;
import ai.devreport.backend.credential.infrastructure.UserAiCredentialRepository;
import ai.devreport.backend.integration.ai.AiProvider;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:ai-credentials;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long",
	"ai.service.mock=true",
	"ai-credentials.keys.1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class AiCredentialIntegrationTest {

	private static final String SECRET_KEY = "AIzaSyTestUserKey-abcdefghijklmnop1234";

	@Autowired
	MockMvc mvc;

	@Autowired
	UserAiCredentialRepository credentials;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void storesOnlyCiphertextAndExposesMaskedHint() throws Exception {
		String token = signupAndLogin("credential-owner@example.com");

		mvc.perform(get("/api/me/ai-credentials").header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty());

		String body = mvc.perform(put("/api/me/ai-credentials/GEMINI")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"apiKey\":\"" + SECRET_KEY + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.provider").value("GEMINI"))
			.andExpect(jsonPath("$.keyHint").value("****1234"))
			.andExpect(jsonPath("$.verifiedAt").isNotEmpty())
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(SECRET_KEY);

		List<UserAiCredential> stored = credentials.findAll().stream()
			.filter(credential -> credential.getKeyHint().equals("1234")).toList();
		assertThat(stored).hasSize(1);
		List<Map<String, Object>> rows = jdbc.queryForList(
			"select * from user_ai_credentials where id = ?", stored.getFirst().getId());
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().toString()).doesNotContain(SECRET_KEY).doesNotContain("abcdefghijklmnop");

		String listBody = mvc.perform(get("/api/me/ai-credentials").header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].provider").value("GEMINI"))
			.andExpect(jsonPath("$.items[0].keyHint").value("****1234"))
			.andReturn().getResponse().getContentAsString();
		assertThat(listBody).doesNotContain(SECRET_KEY);

		mvc.perform(get("/api/ai/models").header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.serverDefault == true)].available").value(true))
			.andExpect(jsonPath("$.items[?(@.serverDefault == true)].usesUserKey").value(true))
			.andExpect(jsonPath("$.items[?(@.model == 'gemini-3.5-pro')].available").value(true));
	}

	@Test
	void treatsUndecryptableKeysAsUnregistered() throws Exception {
		String token = signupAndLogin("credential-unreadable@example.com");
		mvc.perform(put("/api/me/ai-credentials/GEMINI")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"apiKey\":\"AIzaSyUnreadableKey-abcdefghijklmnop5678\"}"))
			.andExpect(status().isOk());
		UserAiCredential stored = credentials.findAll().stream()
			.filter(credential -> credential.getKeyHint().equals("5678")).findFirst().orElseThrow();
		// 마스터 키 rotation에서 이전 버전을 잃어버린 상황을 흉내 낸다.
		jdbc.update("update user_ai_credentials set key_version = 99 where id = ?", stored.getId());

		mvc.perform(get("/api/ai/models").header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.serverDefault == true)].available").value(true))
			.andExpect(jsonPath("$.items[?(@.serverDefault == true)].usesUserKey").value(false))
			.andExpect(jsonPath("$.items[?(@.model == 'gemini-3.5-pro')].available").value(false));
	}

	@Test
	void replacesDeletesAndRejectsInvalidKeys() throws Exception {
		String token = signupAndLogin("credential-rotate@example.com");

		mvc.perform(put("/api/me/ai-credentials/GEMINI")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"apiKey\":\"invalid-key-value-0000\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AI_CREDENTIAL_INVALID"));
		mvc.perform(put("/api/me/ai-credentials/GEMINI")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"apiKey\":\"short\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(put("/api/me/ai-credentials/OPENAI")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"apiKey\":\"" + SECRET_KEY + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		// enum에는 있지만 allowlist에 모델이 없는 provider는 키를 저장하지 않는다.
		mvc.perform(put("/api/me/ai-credentials/ANTHROPIC")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"apiKey\":\"sk-ant-" + SECRET_KEY + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AI_PROVIDER_UNSUPPORTED"));
		// 공백을 빼면 8자 미만인 키는 힌트를 만들 수 없으므로 400으로 거부한다.
		mvc.perform(put("/api/me/ai-credentials/GEMINI")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"apiKey\":\"       a\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AI_CREDENTIAL_INVALID"));
		assertThat(credentials.count()).isZero();

		mvc.perform(put("/api/me/ai-credentials/GEMINI")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"apiKey\":\"" + SECRET_KEY + "\"}"))
			.andExpect(status().isOk());
		UUID firstId = credentials.findAll().getFirst().getId();
		mvc.perform(put("/api/me/ai-credentials/GEMINI")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"apiKey\":\"AIzaSyReplacedKey-zzzzzzzzzzzz9876\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.keyHint").value("****9876"));
		assertThat(credentials.findAll()).hasSize(1);
		assertThat(credentials.findAll().getFirst().getId()).isEqualTo(firstId);

		mvc.perform(delete("/api/me/ai-credentials/GEMINI").header("Authorization", bearer(token)))
			.andExpect(status().isNoContent());
		mvc.perform(delete("/api/me/ai-credentials/GEMINI").header("Authorization", bearer(token)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("AI_CREDENTIAL_NOT_FOUND"));
		assertThat(credentials.existsByUserIdAndProvider(UUID.randomUUID(), AiProvider.GEMINI)).isFalse();

		mvc.perform(get("/api/ai/models").header("Authorization", bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.serverDefault == true)].usesUserKey").value(false))
			.andExpect(jsonPath("$.items[?(@.model == 'gemini-3.5-pro')].available").value(false));
	}

	@Test
	void requiresAuthentication() throws Exception {
		mvc.perform(get("/api/me/ai-credentials")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/ai/models")).andExpect(status().isUnauthorized());
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
}
