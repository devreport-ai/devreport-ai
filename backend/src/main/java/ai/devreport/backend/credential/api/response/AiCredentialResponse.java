package ai.devreport.backend.credential.api.response;

import java.time.Instant;

import ai.devreport.backend.credential.domain.UserAiCredential;
import ai.devreport.backend.integration.ai.AiProvider;

/** 등록 여부와 마스킹된 힌트만 노출한다. 키 원문은 어떤 API로도 돌려주지 않는다. */
public record AiCredentialResponse(AiProvider provider, String keyHint, Instant verifiedAt, Instant createdAt,
	Instant updatedAt) {

	public static AiCredentialResponse from(UserAiCredential credential) {
		return new AiCredentialResponse(credential.getProvider(), "****" + credential.getKeyHint(),
			credential.getVerifiedAt(), credential.getCreatedAt(), credential.getUpdatedAt());
	}
}
