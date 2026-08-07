package ai.devreport.backend.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AuthenticatedUser {

	private AuthenticatedUser() {
	}

	public static UUID id(Jwt jwt) {
		if (jwt == null || jwt.getSubject() == null) {
			throw unauthorized();
		}
		try {
			return UUID.fromString(jwt.getSubject());
		} catch (IllegalArgumentException exception) {
			throw unauthorized();
		}
	}

	private static AuthException unauthorized() {
		return new AuthException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보를 확인할 수 없습니다.");
	}
}
