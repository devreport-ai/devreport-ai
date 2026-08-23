package ai.devreport.backend.project.api.response;

import java.util.List;

import ai.devreport.backend.project.domain.Project;
import org.springframework.data.domain.Page;

public record TrashedProjectPageResponse(List<TrashedProjectResponse> items, int page, int size,
	long totalElements, int totalPages) {
	public static TrashedProjectPageResponse from(Page<Project> projects) {
		return new TrashedProjectPageResponse(
			projects.getContent().stream().map(TrashedProjectResponse::from).toList(),
			projects.getNumber(), projects.getSize(), projects.getTotalElements(), projects.getTotalPages());
	}
}
