package ai.devreport.backend.auth.api.request;

import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
	@AssertTrue(message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
	public boolean isPasswordWithinByteLimit() {
		return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
	}
}
