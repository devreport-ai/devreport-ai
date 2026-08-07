package ai.devreport.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class AuthIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	RefreshTokenRepository refreshTokens;

	@Autowired
	AuthController controller;

	@Autowired
	JwtEncoder jwtEncoder;

	@Test
	void signupLoginRefreshLogoutAndAuthenticationFlow() throws Exception {
		String email = "USER@example.com";

		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123","name":"테스터"}
					""".formatted(email)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value("user@example.com"));
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"bytes@example.com","password":"%s","name":"바이트 검증"}
					""".formatted("가".repeat(25))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		assertThat(users.findByEmail("user@example.com").orElseThrow().getPasswordHash())
			.startsWith("$2").doesNotContain("password123");
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"user@example.com","password":"password123","name":"중복"}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
		mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"user@example.com","password":"wrong-password"}
					"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"missing@example.com","password":"password123"}
					"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"missing@example.com","password":"%s"}
					""".formatted("가".repeat(25))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		String loginBody = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"user@example.com","password":"password123"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andReturn().getResponse().getContentAsString();
		String accessToken = JsonPath.read(loginBody, "$.accessToken");
		String firstRefreshToken = JsonPath.read(loginBody, "$.refreshToken");

		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("user@example.com"));
		mvc.perform(get("/api/auth/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		assertThat(assertThrows(AuthException.class, () -> controller.me(null)).status())
			.isEqualTo(HttpStatus.UNAUTHORIZED);
		Instant now = Instant.now();
		Jwt noSubjectJwt = Jwt.withTokenValue("token")
			.header("alg", "HS256")
			.issuer("devreport-ai")
			.issuedAt(now)
			.expiresAt(now.plusSeconds(300))
			.build();
		assertThat(assertThrows(AuthException.class, () -> controller.me(noSubjectJwt)).status())
			.isEqualTo(HttpStatus.UNAUTHORIZED);

		String userId = users.findByEmail("user@example.com").orElseThrow().getId().toString();
		String wrongIssuerToken = jwtEncoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).build(),
			JwtClaimsSet.builder()
				.issuer("other-service")
				.subject(userId)
				.issuedAt(now)
				.expiresAt(now.plusSeconds(300))
				.build()
		)).getTokenValue();
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + wrongIssuerToken))
			.andExpect(status().isUnauthorized());

		String refreshBody = mvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(firstRefreshToken)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String secondRefreshToken = JsonPath.read(refreshBody, "$.refreshToken");

		mvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(firstRefreshToken)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

		mvc.perform(post("/api/auth/logout")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(secondRefreshToken)))
			.andExpect(status().isNoContent());
		mvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(secondRefreshToken)))
			.andExpect(status().isUnauthorized());

		User user = users.findByEmail("user@example.com").orElseThrow();
		String expiredRawToken = "expired-refresh-token";
		refreshTokens.save(new RefreshToken(user, AuthService.hash(expiredRawToken), Instant.now().minusSeconds(1)));
		mvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(expiredRawToken)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}
}
