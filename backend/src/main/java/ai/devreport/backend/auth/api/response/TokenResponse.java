package ai.devreport.backend.auth.api.response;

import ai.devreport.backend.auth.application.AuthService;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	public static TokenResponse from(AuthService.TokenPair pair) {
		return new TokenResponse(pair.accessToken(), "Bearer", pair.expiresIn());
	}
}
