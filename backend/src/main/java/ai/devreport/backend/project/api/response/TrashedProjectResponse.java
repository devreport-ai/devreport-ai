package ai.devreport.backend.project.api.response;

import java.time.Instant;
import java.util.UUID;

import ai.devreport.backend.project.domain.Project;

public record TrashedProjectResponse(UUID id, String name, Instant deletedAt) {
	public static TrashedProjectResponse from(Project project) {
		return new TrashedProjectResponse(project.getId(), project.getName(), project.getDeletedAt());
	}
}
