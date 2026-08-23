package ai.devreport.backend.auth.api.request;

import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(
	@NotBlank @Size(min = 8, max = 72) String currentPassword,
	@NotBlank @Size(min = 8, max = 72) String newPassword
) {
	@AssertTrue(message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
	public boolean isPasswordWithinByteLimit() {
		return withinByteLimit(currentPassword) && withinByteLimit(newPassword);
	}

	private static boolean withinByteLimit(String password) {
		return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
	}
}
