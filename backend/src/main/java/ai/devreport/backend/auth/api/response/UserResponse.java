package ai.devreport.backend.auth.api.response;

import java.time.Instant;
import java.util.UUID;

import ai.devreport.backend.auth.domain.User;

public record UserResponse(UUID id, String email, String name, Instant createdAt) {
	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getCreatedAt());
	}
}
