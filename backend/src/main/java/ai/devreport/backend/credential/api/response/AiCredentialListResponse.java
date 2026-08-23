package ai.devreport.backend.credential.api.response;

import java.util.List;

import ai.devreport.backend.credential.domain.UserAiCredential;

public record AiCredentialListResponse(List<AiCredentialResponse> items) {

	public static AiCredentialListResponse from(List<UserAiCredential> credentials) {
		return new AiCredentialListResponse(credentials.stream().map(AiCredentialResponse::from).toList());
	}
}
