package ai.devreport.backend.project.api.response;

import java.time.Instant;
import java.util.UUID;

import ai.devreport.backend.project.domain.Project;

public record ProjectResponse(UUID id, String name, UUID ownerId, Instant createdAt, Instant updatedAt) {
	public static ProjectResponse from(Project project) {
		return new ProjectResponse(project.getId(), project.getName(), project.getOwnerId(),
			project.getCreatedAt(), project.getUpdatedAt());
	}
}
