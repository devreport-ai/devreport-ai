package ai.devreport.backend.auth.api.request;

import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
	@NotBlank @Email @Size(max = 320) String email,
	@NotBlank @Size(min = 8, max = 72) String password,
	@NotBlank @Size(max = 100) String name,
	@NotBlank @Size(max = 50) String privacyPolicyVersion,
	@NotBlank @Size(max = 50) String termsOfServiceVersion
) {
	@AssertTrue(message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
	public boolean isPasswordWithinByteLimit() {
		return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
	}
}
