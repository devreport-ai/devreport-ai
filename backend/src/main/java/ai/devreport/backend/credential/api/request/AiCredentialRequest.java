package ai.devreport.backend.credential.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiCredentialRequest(@NotBlank @Size(min = 8, max = 512) String apiKey) {

	/** 로그·디버거에 키가 찍히지 않도록 toString을 가린다. */
	@Override
	public String toString() {
		return "AiCredentialRequest[apiKey=***]";
	}
}
