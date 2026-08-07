package ai.devreport.backend.project;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
class ProjectController {

	private final ProjectService projectService;

	ProjectController(ProjectService projectService) {
		this.projectService = projectService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ProjectIdResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProjectRequest request) {
		return new ProjectIdResponse(projectService.create(AuthenticatedUser.id(jwt), request.name()).getId());
	}

	@GetMapping
	ProjectPageResponse list(@AuthenticationPrincipal Jwt jwt,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ProjectPageResponse.from(projectService.list(AuthenticatedUser.id(jwt), page, size));
	}

	@GetMapping("/trash")
	TrashedProjectPageResponse trash(@AuthenticationPrincipal Jwt jwt,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return TrashedProjectPageResponse.from(projectService.trash(AuthenticatedUser.id(jwt), page, size));
	}

	@GetMapping("/{projectId}")
	ProjectResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
		return ProjectResponse.from(projectService.get(AuthenticatedUser.id(jwt), projectId));
	}

	@PutMapping("/{projectId}")
	ProjectResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
		@Valid @RequestBody ProjectRequest request) {
		return ProjectResponse.from(projectService.update(AuthenticatedUser.id(jwt), projectId, request.name()));
	}

	@DeleteMapping("/{projectId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
		projectService.delete(AuthenticatedUser.id(jwt), projectId);
	}

	@PostMapping("/{projectId}/restore")
	ProjectResponse restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
		return ProjectResponse.from(projectService.restore(AuthenticatedUser.id(jwt), projectId));
	}

	record ProjectRequest(@NotBlank @Size(max = 100) String name) {
	}

	record ProjectIdResponse(UUID projectId) {
	}

	record ProjectResponse(UUID id, String name, UUID ownerId, Instant createdAt, Instant updatedAt) {
		static ProjectResponse from(Project project) {
			return new ProjectResponse(project.getId(), project.getName(), project.getOwnerId(),
				project.getCreatedAt(), project.getUpdatedAt());
		}
	}

	record ProjectPageResponse(List<ProjectResponse> items, int page, int size, long totalElements,
		int totalPages) {
		static ProjectPageResponse from(Page<Project> projects) {
			return new ProjectPageResponse(projects.getContent().stream().map(ProjectResponse::from).toList(),
				projects.getNumber(), projects.getSize(), projects.getTotalElements(), projects.getTotalPages());
		}
	}

	record TrashedProjectResponse(UUID id, String name, Instant deletedAt) {
		static TrashedProjectResponse from(Project project) {
			return new TrashedProjectResponse(project.getId(), project.getName(), project.getDeletedAt());
		}
	}

	record TrashedProjectPageResponse(List<TrashedProjectResponse> items, int page, int size,
		long totalElements, int totalPages) {
		static TrashedProjectPageResponse from(Page<Project> projects) {
			return new TrashedProjectPageResponse(
				projects.getContent().stream().map(TrashedProjectResponse::from).toList(),
				projects.getNumber(), projects.getSize(), projects.getTotalElements(), projects.getTotalPages());
		}
	}
}
