package ai.devreport.backend.project.api.response;

import java.util.List;

import ai.devreport.backend.project.domain.Project;
import org.springframework.data.domain.Page;

public record ProjectPageResponse(List<ProjectResponse> items, int page, int size, long totalElements,
	int totalPages) {
	public static ProjectPageResponse from(Page<Project> projects) {
		return new ProjectPageResponse(projects.getContent().stream().map(ProjectResponse::from).toList(),
			projects.getNumber(), projects.getSize(), projects.getTotalElements(), projects.getTotalPages());
	}
}
