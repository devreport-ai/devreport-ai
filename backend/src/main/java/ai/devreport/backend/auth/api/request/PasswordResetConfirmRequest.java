package ai.devreport.backend.auth.api.request;

import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmRequest(
	@NotBlank @Size(max = 512) String token,
	@NotBlank @Size(max = 72) String newPassword
) {
	@AssertTrue(message = "비밀번호는 8자 이상이어야 합니다.")
	public boolean isPasswordLongEnough() {
		return newPassword == null || newPassword.codePointCount(0, newPassword.length()) >= 8;
	}

	@AssertTrue(message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
	public boolean isPasswordWithinByteLimit() {
		return newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
	}
}
