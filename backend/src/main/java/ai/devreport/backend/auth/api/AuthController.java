package ai.devreport.backend.auth.api;

import ai.devreport.backend.auth.api.request.LoginRequest;
import ai.devreport.backend.auth.api.request.PasswordChangeRequest;
import ai.devreport.backend.auth.api.request.SignupRequest;
import ai.devreport.backend.auth.api.response.TokenResponse;
import ai.devreport.backend.auth.api.response.UserResponse;
import ai.devreport.backend.auth.application.AuthException;
import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.auth.application.AuthService;
import ai.devreport.backend.usage.application.RateLimitService;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

@RestController
@RequestMapping("/api/auth")
class AuthController {

	private final AuthService authService;
	private final RateLimitService rateLimits;
	private final CorsConfigurationSource corsConfigurationSource;
	private final String refreshTokenCookieName;
	private final String refreshTokenCookiePath;
	private final boolean refreshTokenCookieSecure;
	private final String refreshTokenCookieSameSite;
	private final Duration refreshTokenTtl;

	AuthController(AuthService authService, RateLimitService rateLimits,
		CorsConfigurationSource corsConfigurationSource,
		@Value("${auth.refresh-token-cookie.name}") String refreshTokenCookieName,
		@Value("${auth.refresh-token-cookie.path}") String refreshTokenCookiePath,
		@Value("${auth.refresh-token-cookie.secure}") boolean refreshTokenCookieSecure,
		@Value("${auth.refresh-token-cookie.same-site}") String refreshTokenCookieSameSite,
		@Value("${auth.refresh-token-ttl}") Duration refreshTokenTtl) {
		this.authService = authService;
		this.rateLimits = rateLimits;
		this.corsConfigurationSource = corsConfigurationSource;
		this.refreshTokenCookieName = refreshTokenCookieName;
		this.refreshTokenCookiePath = refreshTokenCookiePath;
		this.refreshTokenCookieSecure = refreshTokenCookieSecure;
		this.refreshTokenCookieSameSite = refreshTokenCookieSameSite;
		this.refreshTokenTtl = refreshTokenTtl;
		validateCookieSettings();
	}

	@PostMapping("/signup")
	ResponseEntity<UserResponse> signup(HttpServletRequest servletRequest, @Valid @RequestBody SignupRequest request) {
		rateLimits.checkSignup(clientIp(servletRequest));
		UserResponse response = UserResponse.from(authService.signup(request.email(), request.password(),
			request.name(), request.privacyPolicyVersion(), request.termsOfServiceVersion()));
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PostMapping("/login")
	ResponseEntity<TokenResponse> login(HttpServletRequest servletRequest, @Valid @RequestBody LoginRequest request) {
		validateOrigin(servletRequest);
		rateLimits.checkLogin(clientIp(servletRequest));
		AuthService.TokenPair tokenPair = authService.login(request.email(), request.password());
		return tokenResponse(tokenPair);
	}

	@PostMapping("/refresh")
	ResponseEntity<TokenResponse> refresh(HttpServletRequest servletRequest) {
		validateOrigin(servletRequest);
		rateLimits.checkRefresh(clientIp(servletRequest));
		AuthService.TokenPair tokenPair = authService.refresh(refreshToken(servletRequest));
		return tokenResponse(tokenPair);
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
		validateOrigin(servletRequest);
		try {
			authService.logout(refreshToken(servletRequest));
		} finally {
			servletResponse.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie("", Duration.ZERO));
		}
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void changePassword(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
		@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordChangeRequest request) {
		validateOrigin(servletRequest);
		UUID userId = AuthenticatedUser.id(jwt);
		rateLimits.checkPasswordChange(userId);
		authService.changePassword(userId, request.currentPassword(), request.newPassword());
		servletResponse.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie("", Duration.ZERO));
	}

	@GetMapping("/me")
	ResponseEntity<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
		if (jwt == null || jwt.getSubject() == null) {
			throw unauthorized();
		}
		try {
			return ResponseEntity.ok(UserResponse.from(authService.getUser(UUID.fromString(jwt.getSubject()))));
		} catch (IllegalArgumentException exception) {
			throw unauthorized();
		}
	}

	private ResponseEntity<TokenResponse> tokenResponse(AuthService.TokenPair tokenPair) {
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, refreshTokenCookie(tokenPair.refreshToken(), refreshTokenTtl))
			.body(TokenResponse.from(tokenPair));
	}

	private static AuthException unauthorized() {
		return new AuthException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보를 확인할 수 없습니다.");
	}

	private static String clientIp(HttpServletRequest request) {
		// Spring's configured forwarded-header strategy resolves the trusted proxy address.
		return request.getRemoteAddr();
	}

	private String refreshToken(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (refreshTokenCookieName.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private String refreshTokenCookie(String value, Duration maxAge) {
		return ResponseCookie.from(refreshTokenCookieName, value)
			.httpOnly(true)
			.secure(refreshTokenCookieSecure)
			.sameSite(refreshTokenCookieSameSite)
			.path(refreshTokenCookiePath)
			.maxAge(maxAge)
			.build()
			.toString();
	}

	private void validateOrigin(HttpServletRequest request) {
		Optional<String> requestOrigin = requestOrigin(request);
		if (requestOrigin.isEmpty()) {
			// Modern browsers send Origin on cross-site POSTs, so keep this compatibility path for requests without either header.
			return;
		}
		String origin = requestOrigin.get();
		CorsConfiguration corsConfiguration = corsConfigurationSource.getCorsConfiguration(request);
		boolean allowed = sameOrigin(request, origin)
			|| (corsConfiguration != null && corsConfiguration.checkOrigin(origin) != null);
		if (!allowed) {
			throw new AuthException(HttpStatus.FORBIDDEN, "CSRF_ORIGIN_INVALID", "허용되지 않은 Origin입니다.");
		}
	}

	private static Optional<String> requestOrigin(HttpServletRequest request) {
		String origin = request.getHeader("Origin");
		if (origin != null) {
			return Optional.of(origin);
		}
		String referer = request.getHeader("Referer");
		if (referer == null) {
			return Optional.empty();
		}
		try {
			URI uri = URI.create(referer);
			if (uri.getScheme() == null || uri.getRawAuthority() == null) {
				return Optional.of("");
			}
			return Optional.of(uri.getScheme() + "://" + uri.getRawAuthority());
		} catch (IllegalArgumentException exception) {
			return Optional.of("");
		}
	}

	private static boolean sameOrigin(HttpServletRequest request, String origin) {
		try {
			URI uri = URI.create(origin);
			if (uri.getHost() == null || uri.getUserInfo() != null
				|| uri.getPath() != null && !uri.getPath().isEmpty()
				|| uri.getQuery() != null || uri.getFragment() != null) {
				return false;
			}
			if (!request.getScheme().equalsIgnoreCase(uri.getScheme())
				|| !request.getServerName().equalsIgnoreCase(uri.getHost())) {
				return false;
			}
			return effectivePort(request.getScheme(), request.getServerPort())
				== effectivePort(uri.getScheme(), uri.getPort());
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static int effectivePort(String scheme, int port) {
		if (port > 0) {
			return port;
		}
		return "https".equalsIgnoreCase(scheme) ? 443 : 80;
	}

	private void validateCookieSettings() {
		if (refreshTokenCookieName.isBlank() || !refreshTokenCookiePath.startsWith("/api/auth")
			|| refreshTokenTtl.isZero() || refreshTokenTtl.isNegative()
			|| !Set.of("strict", "lax", "none").contains(refreshTokenCookieSameSite.toLowerCase(Locale.ROOT))) {
			throw new IllegalStateException("Refresh Token 쿠키 설정이 올바르지 않습니다.");
		}
		if ("none".equalsIgnoreCase(refreshTokenCookieSameSite) && !refreshTokenCookieSecure) {
			throw new IllegalStateException("SameSite=None 쿠키는 Secure여야 합니다.");
		}
	}
}
