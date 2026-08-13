package ai.devreport.backend.auth.api;

import ai.devreport.backend.auth.application.AuthException;
import ai.devreport.backend.auth.application.AuthService;
import ai.devreport.backend.auth.domain.User;
import ai.devreport.backend.usage.application.RateLimitService;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

	private final AuthService authService;
	private final RateLimitService rateLimits;

	AuthController(AuthService authService, RateLimitService rateLimits) {
		this.authService = authService;
		this.rateLimits = rateLimits;
	}

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	UserResponse signup(HttpServletRequest servletRequest, @Valid @RequestBody SignupRequest request) {
		rateLimits.checkSignup(clientIp(servletRequest));
		return UserResponse.from(authService.signup(request.email(), request.password(), request.name()));
	}

	@PostMapping("/login")
	TokenResponse login(HttpServletRequest servletRequest, @Valid @RequestBody LoginRequest request) {
		rateLimits.checkLogin(clientIp(servletRequest));
		return TokenResponse.from(authService.login(request.email(), request.password()));
	}

	@PostMapping("/refresh")
	TokenResponse refresh(HttpServletRequest servletRequest, @Valid @RequestBody RefreshRequest request) {
		rateLimits.checkRefresh(clientIp(servletRequest));
		return TokenResponse.from(authService.refresh(request.refreshToken()));
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void logout(@Valid @RequestBody RefreshRequest request) {
		authService.logout(request.refreshToken());
	}

	@GetMapping("/me")
	UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		if (jwt == null || jwt.getSubject() == null) {
			throw unauthorized();
		}
		try {
			return UserResponse.from(authService.getUser(UUID.fromString(jwt.getSubject())));
		} catch (IllegalArgumentException exception) {
			throw unauthorized();
		}
	}

	private static AuthException unauthorized() {
		return new AuthException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보를 확인할 수 없습니다.");
	}

	private static String clientIp(HttpServletRequest request) {
		// Spring's configured forwarded-header strategy resolves the trusted proxy address.
		return request.getRemoteAddr();
	}

	record SignupRequest(
		@NotBlank @Email @Size(max = 320) String email,
		@NotBlank @Size(min = 8, max = 72) String password,
		@NotBlank @Size(max = 100) String name
	) {
		@AssertTrue(message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
		public boolean isPasswordWithinByteLimit() {
			return password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
		}
	}

	record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
		@AssertTrue(message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
		public boolean isPasswordWithinByteLimit() {
			return password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
		}
	}

	record RefreshRequest(@NotBlank String refreshToken) {
	}

	record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
		static TokenResponse from(AuthService.TokenPair pair) {
			return new TokenResponse(pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresIn());
		}
	}

	record UserResponse(UUID id, String email, String name, Instant createdAt) {
		static UserResponse from(User user) {
			return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getCreatedAt());
		}
	}
}
