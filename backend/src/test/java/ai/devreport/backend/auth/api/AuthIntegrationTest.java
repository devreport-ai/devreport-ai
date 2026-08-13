package ai.devreport.backend.auth.api;

import ai.devreport.backend.auth.application.AuthException;
import ai.devreport.backend.auth.application.AuthService;
import ai.devreport.backend.auth.domain.RefreshToken;
import ai.devreport.backend.auth.domain.User;
import ai.devreport.backend.auth.infrastructure.RefreshTokenRepository;
import ai.devreport.backend.auth.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long",
		"security.headers.enabled=true"
})
@AutoConfigureMockMvc
class AuthIntegrationTest {

	private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

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

		MvcResult loginResult = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"user@example.com","password":"password123"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andReturn();
		String loginBody = loginResult.getResponse().getContentAsString();
		String accessToken = JsonPath.read(loginBody, "$.accessToken");
		Cookie firstRefreshCookie = loginResult.getResponse().getCookie(REFRESH_TOKEN_COOKIE);
		assertThat(firstRefreshCookie).isNotNull();
		assertThat(firstRefreshCookie.isHttpOnly()).isTrue();
		assertThat(firstRefreshCookie.getSecure()).isFalse();
		assertThat(firstRefreshCookie.getPath()).isEqualTo("/api/auth");
		assertThat(loginResult.getResponse().getHeader(HttpHeaders.SET_COOKIE))
			.contains("SameSite=Lax", "Max-Age=1209600");

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

		mvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(firstRefreshCookie.getValue())))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

		MvcResult refreshResult = mvc.perform(post("/api/auth/refresh").cookie(firstRefreshCookie))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andReturn();
		Cookie secondRefreshCookie = refreshResult.getResponse().getCookie(REFRESH_TOKEN_COOKIE);
		assertThat(secondRefreshCookie).isNotNull();
		assertThat(secondRefreshCookie.getValue()).isNotEqualTo(firstRefreshCookie.getValue());

		mvc.perform(post("/api/auth/refresh").cookie(firstRefreshCookie))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

		MvcResult logoutResult = mvc.perform(post("/api/auth/logout").cookie(secondRefreshCookie))
			.andExpect(status().isNoContent())
			.andReturn();
		assertThat(logoutResult.getResponse().getHeader(HttpHeaders.SET_COOKIE))
			.contains("Max-Age=0", "HttpOnly", "Path=/api/auth");
		mvc.perform(post("/api/auth/refresh").cookie(secondRefreshCookie))
			.andExpect(status().isUnauthorized());

		User user = users.findByEmail("user@example.com").orElseThrow();
		String expiredRawToken = "expired-refresh-token";
		refreshTokens.save(new RefreshToken(user, AuthService.hash(expiredRawToken), Instant.now().minusSeconds(1)));
		mvc.perform(post("/api/auth/refresh")
				.cookie(new Cookie(REFRESH_TOKEN_COOKIE, expiredRawToken)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void rejectsDisallowedOriginButAllowsConfiguredCredentialedOrigin() throws Exception {
		mvc.perform(options("/api/auth/refresh")
				.header("Origin", "http://localhost:3000")
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "content-type"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
			.andExpect(header().string("Access-Control-Allow-Credentials", "true"));

		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"origin@example.com","password":"password123","name":"Origin"}
					"""))
			.andExpect(status().isCreated());
		MvcResult loginResult = mvc.perform(post("/api/auth/login")
				.header("Origin", "http://localhost:3000")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"origin@example.com","password":"password123"}
					"""))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
			.andExpect(header().string("Access-Control-Allow-Credentials", "true"))
			.andReturn();
		Cookie refreshCookie = loginResult.getResponse().getCookie(REFRESH_TOKEN_COOKIE);
		assertThat(refreshCookie).isNotNull();

		mvc.perform(post("/api/auth/refresh")
				.header("Origin", "https://evil.example")
				.cookie(refreshCookie))
			.andExpect(status().isForbidden());
	}

	@Test
	void includesBaselineSecurityHeaders() throws Exception {
		MvcResult result = mvc.perform(get("/actuator/health").secure(true))
			.andExpect(status().isOk())
			.andReturn();

		assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(result.getResponse().getHeader("Content-Security-Policy"))
			.contains("default-src 'self'");
		assertThat(result.getResponse().getHeader("Strict-Transport-Security"))
			.contains("max-age=31536000", "includeSubDomains");
	}

	@Test
	void validatesRefererWhenOriginHeaderIsAbsent() throws Exception {
		mvc.perform(post("/api/auth/refresh")
				.header("Referer", "https://evil.example/attack")
				.cookie(new Cookie(REFRESH_TOKEN_COOKIE, "any-value")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CSRF_ORIGIN_INVALID"));

		mvc.perform(post("/api/auth/refresh")
				.header("Referer", "not-a-valid-url")
				.cookie(new Cookie(REFRESH_TOKEN_COOKIE, "any-value")))
			.andExpect(status().isForbidden());

		mvc.perform(post("/api/auth/refresh")
				.header("Referer", "http://localhost:3000/login")
				.cookie(new Cookie(REFRESH_TOKEN_COOKIE, "any-value")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void logoutClearsCookieEvenWhenServerTokenIsMissing() throws Exception {
		MvcResult result = mvc.perform(post("/api/auth/logout")
				.cookie(new Cookie(REFRESH_TOKEN_COOKIE, "already-revoked")))
			.andExpect(status().isNoContent())
			.andReturn();

		assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
			.contains("Max-Age=0", "Path=/api/auth");
	}
}
