package ai.devreport.backend.auth.application;

import static ai.devreport.backend.config.SecurityConfig.JWT_ISSUER;

import ai.devreport.backend.auth.domain.RefreshToken;
import ai.devreport.backend.auth.domain.PasswordResetToken;
import ai.devreport.backend.auth.domain.PolicyConsent;
import ai.devreport.backend.auth.domain.PolicyVersions;
import ai.devreport.backend.auth.domain.User;
import ai.devreport.backend.auth.infrastructure.PolicyConsentRepository;
import ai.devreport.backend.auth.infrastructure.PasswordResetTokenRepository;
import ai.devreport.backend.auth.infrastructure.RefreshTokenRepository;
import ai.devreport.backend.auth.infrastructure.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuthService {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String DUMMY_PASSWORD_HASH =
		"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final UserRepository users;
	private final PolicyConsentRepository policyConsents;
	private final RefreshTokenRepository refreshTokens;
	private final PasswordResetTokenRepository passwordResetTokens;
	private final PasswordEncoder passwordEncoder;
	private final JwtEncoder jwtEncoder;
	private final Duration accessTokenTtl;
	private final Duration refreshTokenTtl;
	private final Duration passwordResetTokenTtl;

	AuthService(UserRepository users, PolicyConsentRepository policyConsents, RefreshTokenRepository refreshTokens,
		PasswordResetTokenRepository passwordResetTokens,
		PasswordEncoder passwordEncoder,
		JwtEncoder jwtEncoder, @Value("${auth.access-token-ttl}") Duration accessTokenTtl,
		@Value("${auth.refresh-token-ttl}") Duration refreshTokenTtl,
		@Value("${auth.password-reset.token-ttl}") Duration passwordResetTokenTtl) {
		this.users = users;
		this.policyConsents = policyConsents;
		this.refreshTokens = refreshTokens;
		this.passwordResetTokens = passwordResetTokens;
		this.passwordEncoder = passwordEncoder;
		this.jwtEncoder = jwtEncoder;
		this.accessTokenTtl = accessTokenTtl;
		this.refreshTokenTtl = refreshTokenTtl;
		this.passwordResetTokenTtl = passwordResetTokenTtl;
	}

	public User signup(String email, String password, String name, String privacyPolicyVersion,
		String termsOfServiceVersion) {
		String normalizedEmail = normalizeEmail(email);
		validatePolicyVersions(privacyPolicyVersion, termsOfServiceVersion);
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
		}
		if (users.findByEmail(normalizedEmail).isPresent()) {
			throw new AuthException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다.");
		}
		User user;
		try {
			user = users.saveAndFlush(new User(normalizedEmail, passwordEncoder.encode(password), name.trim()));
		} catch (DataIntegrityViolationException exception) {
			throw new AuthException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다.");
		}
		policyConsents.save(new PolicyConsent(user, privacyPolicyVersion, termsOfServiceVersion, Instant.now()));
		return user;
	}

	private static void validatePolicyVersions(String privacyPolicyVersion, String termsOfServiceVersion) {
		if (!PolicyVersions.PRIVACY_POLICY.equals(privacyPolicyVersion)
			|| !PolicyVersions.TERMS_OF_SERVICE.equals(termsOfServiceVersion)) {
			throw new AuthException(HttpStatus.BAD_REQUEST, "POLICY_CONSENT_REQUIRED",
				"현재 개인정보처리방침과 이용약관에 동의해야 합니다.");
		}
	}

	public TokenPair login(String email, String password) {
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw invalidCredentials();
		}
		Optional<User> user = users.findByEmail(normalizeEmail(email));
		boolean matches = passwordEncoder.matches(password,
			user.map(User::getPasswordHash).orElse(DUMMY_PASSWORD_HASH));
		if (user.isEmpty() || !matches) {
			throw invalidCredentials();
		}
		return issueTokens(user.get());
	}

	public void changePassword(UUID userId, String currentPassword, String newPassword) {
		if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD",
				"비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
		}
		User user = users.findForUpdate(userId)
			.orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보를 확인할 수 없습니다."));
		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD",
				"현재 비밀번호가 올바르지 않습니다.");
		}
		if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			throw new AuthException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED",
				"새 비밀번호는 기존 비밀번호와 달라야 합니다.");
		}
		user.changePassword(passwordEncoder.encode(newPassword));
		refreshTokens.revokeAllByUserId(userId, Instant.now());
	}

	public Optional<PasswordResetEmail> createPasswordReset(String email) {
		return users.findByEmailForUpdate(normalizeEmail(email)).map(user -> {
			Instant now = Instant.now();
			passwordResetTokens.useAllByUserId(user.getId(), now);
			String rawToken = randomToken();
			passwordResetTokens.save(new PasswordResetToken(user, hash(rawToken), now.plus(passwordResetTokenTtl)));
			return new PasswordResetEmail(user.getEmail(), rawToken);
		});
	}

	public void resetPassword(String rawToken, String newPassword) {
		validateNewPassword(newPassword);
		String tokenHash = hash(rawToken);
		UUID userId = passwordResetTokens.findUserIdByTokenHash(tokenHash)
			.orElseThrow(AuthService::invalidPasswordResetToken);
		User user = users.findForUpdate(userId).orElseThrow(AuthService::invalidPasswordResetToken);
		PasswordResetToken token = passwordResetTokens.findByTokenHash(tokenHash)
			.filter(candidate -> candidate.isUsable(Instant.now()))
			.orElseThrow(AuthService::invalidPasswordResetToken);
		if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			throw new AuthException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED",
				"새 비밀번호는 기존 비밀번호와 달라야 합니다.");
		}
		Instant now = Instant.now();
		user.changePassword(passwordEncoder.encode(newPassword));
		passwordResetTokens.useAllByUserId(token.getUser().getId(), now);
		refreshTokens.revokeAllByUserId(userId, now);
	}

	public TokenPair refresh(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			throw invalidRefreshToken();
		}
		String tokenHash = hash(rawToken);
		UUID userId = refreshTokens.findUserIdByTokenHash(tokenHash)
			.orElseThrow(AuthService::invalidRefreshToken);
		User user = users.findForUpdate(userId)
			.orElseThrow(AuthService::invalidRefreshToken);
		Instant now = Instant.now();
		RefreshToken refreshToken = refreshTokens.findByTokenHash(tokenHash)
			.filter(token -> token.isUsable(now))
			.orElseThrow(AuthService::invalidRefreshToken);
		refreshToken.revoke(now);
		return issueTokens(user);
	}

	public void logout(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return;
		}
		refreshTokens.findByTokenHash(hash(rawToken))
			.filter(token -> token.isUsable(Instant.now()))
			.ifPresent(token -> token.revoke(Instant.now()));
	}

	@Transactional(readOnly = true)
	public User getUser(UUID id) {
		return users.findById(id)
			.orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보를 확인할 수 없습니다."));
	}

	private TokenPair issueTokens(User user) {
		Instant now = Instant.now();
		String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).build(),
			JwtClaimsSet.builder()
				.issuer(JWT_ISSUER)
				.subject(user.getId().toString())
				.issuedAt(now)
				.expiresAt(now.plus(accessTokenTtl))
				.claim("email", user.getEmail())
				.build()
		)).getTokenValue();

		String rawRefreshToken = randomToken();
		refreshTokens.save(new RefreshToken(user, hash(rawRefreshToken), now.plus(refreshTokenTtl)));
		return new TokenPair(accessToken, rawRefreshToken, accessTokenTtl.toSeconds());
	}

	public static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static void validateNewPassword(String password) {
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD",
				"비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
		}
	}

	public static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static AuthException invalidCredentials() {
		return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
	}

	private static AuthException invalidRefreshToken() {
		return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh Token이 유효하지 않습니다.");
	}

	private static AuthException invalidPasswordResetToken() {
		return new AuthException(HttpStatus.BAD_REQUEST, "PASSWORD_RESET_TOKEN_INVALID",
			"비밀번호 재설정 링크가 만료되었거나 유효하지 않습니다.");
	}

	public record TokenPair(String accessToken, String refreshToken, long expiresIn) {
	}

	public record PasswordResetEmail(String email, String token) {
	}
}
