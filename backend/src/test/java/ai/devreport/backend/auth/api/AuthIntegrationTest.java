package ai.devreport.backend.auth.api;

import ai.devreport.backend.auth.application.AuthException;
import ai.devreport.backend.auth.application.AuthService;
import ai.devreport.backend.auth.domain.PolicyConsent;
import ai.devreport.backend.auth.domain.PolicyVersions;
import ai.devreport.backend.auth.domain.PasswordResetToken;
import ai.devreport.backend.auth.domain.RefreshToken;
import ai.devreport.backend.auth.domain.User;
import ai.devreport.backend.auth.infrastructure.PolicyConsentRepository;
import ai.devreport.backend.auth.infrastructure.PasswordResetEmailSender;
import ai.devreport.backend.auth.infrastructure.PasswordResetTokenRepository;
import ai.devreport.backend.auth.infrastructure.RefreshTokenRepository;
import ai.devreport.backend.auth.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;

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
	PasswordResetTokenRepository passwordResetTokens;

	@MockitoBean
	PasswordResetEmailSender passwordResetEmailSender;

	@Autowired
	PolicyConsentRepository policyConsents;

	@Autowired
	AuthService authService;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	AuthController controller;

	@Autowired
	JwtEncoder jwtEncoder;

	@Test
	void signupRequiresCurrentPolicyVersionsAndRecordsServerConsentTime() throws Exception {
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"missing-consent@example.com","password":"password123","name":"미동의"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"old-policy@example.com","password":"password123","name":"구버전",
					"privacyPolicyVersion":"2026-01-01","termsOfServiceVersion":"2026-01-01"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("POLICY_CONSENT_REQUIRED"));

		Instant consentRequestStartedAt = Instant.now();
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"current-policy@example.com","password":"password123","name":"현재 정책",
					"privacyPolicyVersion":"%s","termsOfServiceVersion":"%s"}
					""".formatted(PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE)))
			.andExpect(status().isCreated());
		Instant consentRequestFinishedAt = Instant.now();

		User user = users.findByEmail("current-policy@example.com").orElseThrow();
		PolicyConsent consent = policyConsents.findAll().stream()
			.filter(candidate -> candidate.getUser().getId().equals(user.getId()))
			.findFirst().orElseThrow();
		assertThat(consent.getPrivacyPolicyVersion()).isEqualTo(PolicyVersions.PRIVACY_POLICY);
		assertThat(consent.getTermsOfServiceVersion()).isEqualTo(PolicyVersions.TERMS_OF_SERVICE);
		assertThat(consent.getConsentedAt()).isBetween(consentRequestStartedAt.minusSeconds(1),
			consentRequestFinishedAt.plusSeconds(1));
	}

	@Test
	void signupLoginRefreshLogoutAndAuthenticationFlow() throws Exception {
		String email = "USER@example.com";

		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123","name":"테스터","privacyPolicyVersion":"%s","termsOfServiceVersion":"%s"}
					""".formatted(email, PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value("user@example.com"));
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"bytes@example.com","password":"%s","name":"바이트 검증","privacyPolicyVersion":"%s","termsOfServiceVersion":"%s"}
					""".formatted("가".repeat(25), PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		assertThat(users.findByEmail("user@example.com").orElseThrow().getPasswordHash())
			.startsWith("$2").doesNotContain("password123");
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"user@example.com","password":"password123","name":"중복","privacyPolicyVersion":"%s","termsOfServiceVersion":"%s"}
					""".formatted(PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE)))
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
					{"email":"origin@example.com","password":"password123","name":"Origin","privacyPolicyVersion":"%s","termsOfServiceVersion":"%s"}
					""".formatted(PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE)))
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

	@Test
	void changesPasswordAndRevokesAllRefreshTokens() throws Exception {
		String email = "password-change@example.com";
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123","name":"비밀번호 변경",
					"privacyPolicyVersion":"%s","termsOfServiceVersion":"%s"}
					""".formatted(email, PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE)))
			.andExpect(status().isCreated());

		MvcResult firstLogin = login(email, "password123");
		MvcResult secondLogin = login(email, "password123");
		String accessToken = JsonPath.read(firstLogin.getResponse().getContentAsString(), "$.accessToken");
		Cookie firstRefresh = firstLogin.getResponse().getCookie(REFRESH_TOKEN_COOKIE);
		Cookie secondRefresh = secondLogin.getResponse().getCookie(REFRESH_TOKEN_COOKIE);
		assertThat(firstRefresh).isNotNull();
		assertThat(secondRefresh).isNotNull();

		mvc.perform(post("/api/auth/password")
				.header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"currentPassword":"password123","newPassword":"new-password123"}
					"""))
			.andExpect(status().isNoContent())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

		mvc.perform(post("/api/auth/refresh").cookie(firstRefresh))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
		mvc.perform(post("/api/auth/refresh").cookie(secondRefresh))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
		login(email, "password123", status().isUnauthorized());
		login(email, "new-password123", status().isOk());

		assertThat(users.findByEmail(email).orElseThrow().getPasswordHash())
			.startsWith("$2").doesNotContain("new-password123");
	}

	@Test
	void blocksRefreshWhilePasswordChangeHoldsUserLock() throws Exception {
		String email = "password-refresh-race@example.com";
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123","name":"동시성 테스트",
					"privacyPolicyVersion":"%s","termsOfServiceVersion":"%s"}
					""".formatted(email, PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE)))
			.andExpect(status().isCreated());
		AuthService.TokenPair tokenPair = authService.login(email, "password123");
		UUID userId = users.findByEmail(email).orElseThrow().getId();
		CountDownLatch userLockAcquired = new CountDownLatch(1);
		CountDownLatch releasePasswordChange = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> passwordChange = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
				users.findForUpdate(userId).orElseThrow();
				userLockAcquired.countDown();
				awaitLatch(releasePasswordChange);
				authService.changePassword(userId, "password123", "new-password123");
				return null;
			}));
			assertThat(userLockAcquired.await(2, TimeUnit.SECONDS)).isTrue();

			Future<AuthService.TokenPair> refresh = executor.submit(() -> authService.refresh(tokenPair.refreshToken()));
			assertThrows(TimeoutException.class, () -> refresh.get(1, TimeUnit.SECONDS));

			releasePasswordChange.countDown();
			passwordChange.get(2, TimeUnit.SECONDS);
			ExecutionException refreshFailure = assertThrows(ExecutionException.class,
				() -> refresh.get(2, TimeUnit.SECONDS));
			assertThat(refreshFailure.getCause()).isInstanceOf(AuthException.class);
			Instant now = Instant.now();
			assertThat(refreshTokens.findAll().stream()
				.filter(token -> token.getUser().getId().equals(userId))
				.noneMatch(token -> token.isUsable(now))).isTrue();
		} finally {
			releasePasswordChange.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void rejectsUnchangedPasswordAndRateLimitsRepeatedPasswordChanges() throws Exception {
		String email = "password-rate-limit@example.com";
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123","name":"비밀번호 제한",
					"privacyPolicyVersion":"%s","termsOfServiceVersion":"%s"}
					""".formatted(email, PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE)))
			.andExpect(status().isCreated());
		MvcResult login = login(email, "password123");
		String accessToken = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");

		mvc.perform(post("/api/auth/password")
				.header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"currentPassword":"password123","newPassword":"password123"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PASSWORD_UNCHANGED"));

		for (int attempt = 0; attempt < 4; attempt++) {
			mvc.perform(post("/api/auth/password")
					.header("Authorization", "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"currentPassword":"wrong-password","newPassword":"new-password123"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
		}
		mvc.perform(post("/api/auth/password")
				.header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"currentPassword":"wrong-password","newPassword":"new-password123"}
					"""))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
	}

	@Test
	void passwordResetUsesLatestTokenOnceAndRevokesRefreshTokens() throws Exception {
		reset(passwordResetEmailSender);
		String email = "password-reset@example.com";
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123","name":"재설정 사용자",
					"privacyPolicyVersion":"%s","termsOfServiceVersion":"%s"}
					""".formatted(email, PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE)))
			.andExpect(status().isCreated());
		MvcResult login = login(email, "password123");
		Cookie refreshCookie = login.getResponse().getCookie(REFRESH_TOKEN_COOKIE);

		String acceptedMessage = mvc.perform(post("/api/auth/password-reset/request")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\"}".formatted(email)))
			.andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString();
		String missingMessage = mvc.perform(post("/api/auth/password-reset/request")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"missing-reset@example.com\"}"))
			.andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString();
		assertThat(missingMessage).isEqualTo(acceptedMessage);

		mvc.perform(post("/api/auth/password-reset/request")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\"}".formatted(email)))
			.andExpect(status().isAccepted());
		ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
		verify(passwordResetEmailSender, times(2)).send(eq(email), tokens.capture());
		String firstToken = tokens.getAllValues().get(0);
		String latestToken = tokens.getAllValues().get(1);
		assertThat(passwordResetTokens.findAll())
			.extracting(PasswordResetToken::getTokenHash)
			.contains(AuthService.hash(firstToken), AuthService.hash(latestToken))
			.doesNotContain(firstToken, latestToken);

		mvc.perform(post("/api/auth/password-reset/confirm")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"%s\",\"newPassword\":\"new-password123\"}".formatted(firstToken)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"));
		mvc.perform(post("/api/auth/password-reset/confirm")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"%s\",\"newPassword\":\"new-password123\"}".formatted(latestToken)))
			.andExpect(status().isNoContent())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

		login(email, "password123", status().isUnauthorized());
		login(email, "new-password123");
		mvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/password-reset/confirm")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"%s\",\"newPassword\":\"another-password123\"}".formatted(latestToken)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"));
	}

	@Test
	void rejectsExpiredForgedAndRateLimitedPasswordResetRequests() throws Exception {
		String email = "expired-reset@example.com";
		User user = authService.signup(email, "password123", "만료 사용자",
			PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE);
		passwordResetTokens.save(new PasswordResetToken(user, AuthService.hash("expired-token"),
			Instant.now().minusSeconds(1)));

		for (String token : new String[] {"expired-token", "forged-token"}) {
			mvc.perform(post("/api/auth/password-reset/confirm")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"token\":\"%s\",\"newPassword\":\"new-password123\"}".formatted(token)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"));
		}

		for (int attempt = 0; attempt < 5; attempt++) {
			mvc.perform(post("/api/auth/password-reset/request")
					.with(request -> {
						request.setRemoteAddr("198.51.100.119");
						return request;
					})
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"rate-%d@example.com\"}".formatted(attempt)))
				.andExpect(status().isAccepted());
		}
		mvc.perform(post("/api/auth/password-reset/request")
				.with(request -> {
					request.setRemoteAddr("198.51.100.119");
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"rate-final@example.com\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
	}

	private MvcResult login(String email, String password) throws Exception {
		return login(email, password, status().isOk());
	}

	private MvcResult login(String email, String password, org.springframework.test.web.servlet.ResultMatcher expected)
		throws Exception {
		return mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
			.andExpect(expected)
			.andReturn();
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			if (!latch.await(2, TimeUnit.SECONDS)) {
				throw new IllegalStateException("동시성 테스트 해제 신호가 만료되었습니다.");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("동시성 테스트가 중단되었습니다.", exception);
		}
	}
}
