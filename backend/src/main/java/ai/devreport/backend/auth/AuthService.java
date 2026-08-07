package ai.devreport.backend.auth;

import static ai.devreport.backend.config.SecurityConfig.JWT_ISSUER;

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
class AuthService {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String DUMMY_PASSWORD_HASH =
		"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final UserRepository users;
	private final RefreshTokenRepository refreshTokens;
	private final PasswordEncoder passwordEncoder;
	private final JwtEncoder jwtEncoder;
	private final Duration accessTokenTtl;
	private final Duration refreshTokenTtl;

	AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
		JwtEncoder jwtEncoder, @Value("${auth.access-token-ttl}") Duration accessTokenTtl,
		@Value("${auth.refresh-token-ttl}") Duration refreshTokenTtl) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.passwordEncoder = passwordEncoder;
		this.jwtEncoder = jwtEncoder;
		this.accessTokenTtl = accessTokenTtl;
		this.refreshTokenTtl = refreshTokenTtl;
	}

	User signup(String email, String password, String name) {
		String normalizedEmail = normalizeEmail(email);
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
		}
		if (users.findByEmail(normalizedEmail).isPresent()) {
			throw new AuthException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다.");
		}
		try {
			return users.saveAndFlush(new User(normalizedEmail, passwordEncoder.encode(password), name.trim()));
		} catch (DataIntegrityViolationException exception) {
			throw new AuthException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다.");
		}
	}

	TokenPair login(String email, String password) {
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

	TokenPair refresh(String rawToken) {
		Instant now = Instant.now();
		RefreshToken refreshToken = refreshTokens.findByTokenHash(hash(rawToken))
			.filter(token -> token.isUsable(now))
			.orElseThrow(AuthService::invalidRefreshToken);
		refreshToken.revoke(now);
		return issueTokens(refreshToken.getUser());
	}

	void logout(String rawToken) {
		refreshTokens.findByTokenHash(hash(rawToken))
			.filter(token -> token.isUsable(Instant.now()))
			.ifPresent(token -> token.revoke(Instant.now()));
	}

	@Transactional(readOnly = true)
	User getUser(UUID id) {
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

		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String rawRefreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		refreshTokens.save(new RefreshToken(user, hash(rawRefreshToken), now.plus(refreshTokenTtl)));
		return new TokenPair(accessToken, rawRefreshToken, accessTokenTtl.toSeconds());
	}

	private static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	static String hash(String token) {
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

	record TokenPair(String accessToken, String refreshToken, long expiresIn) {
	}
}
