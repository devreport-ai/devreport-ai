package ai.devreport.backend.project.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.project.api.request.ProjectRequest;
import ai.devreport.backend.project.api.response.ProjectIdResponse;
import ai.devreport.backend.project.api.response.ProjectPageResponse;
import ai.devreport.backend.project.api.response.ProjectResponse;
import ai.devreport.backend.project.api.response.TrashedProjectPageResponse;
import ai.devreport.backend.project.application.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
class ProjectController {

	private final ProjectService projectService;

	ProjectController(ProjectService projectService) {
		this.projectService = projectService;
	}

	@PostMapping
	ResponseEntity<ProjectIdResponse> create(@AuthenticationPrincipal Jwt jwt,
		@Valid @RequestBody ProjectRequest request) {
		UUID projectId = projectService.create(AuthenticatedUser.id(jwt), request.name()).getId();
		return ResponseEntity.status(HttpStatus.CREATED).body(new ProjectIdResponse(projectId));
	}

	@GetMapping
	ResponseEntity<ProjectPageResponse> list(@AuthenticationPrincipal Jwt jwt,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ResponseEntity.ok(ProjectPageResponse.from(projectService.list(AuthenticatedUser.id(jwt), page, size)));
	}

	@GetMapping("/trash")
	ResponseEntity<TrashedProjectPageResponse> trash(@AuthenticationPrincipal Jwt jwt,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ResponseEntity.ok(
			TrashedProjectPageResponse.from(projectService.trash(AuthenticatedUser.id(jwt), page, size)));
	}

	@GetMapping("/{projectId}")
	ResponseEntity<ProjectResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
		return ResponseEntity.ok(ProjectResponse.from(projectService.get(AuthenticatedUser.id(jwt), projectId)));
	}

	@PutMapping("/{projectId}")
	ResponseEntity<ProjectResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
		@Valid @RequestBody ProjectRequest request) {
		return ResponseEntity.ok(
			ProjectResponse.from(projectService.update(AuthenticatedUser.id(jwt), projectId, request.name())));
	}

	@DeleteMapping("/{projectId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
		projectService.delete(AuthenticatedUser.id(jwt), projectId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{projectId}/restore")
	ResponseEntity<ProjectResponse> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
		return ResponseEntity.ok(ProjectResponse.from(projectService.restore(AuthenticatedUser.id(jwt), projectId)));
	}
}
