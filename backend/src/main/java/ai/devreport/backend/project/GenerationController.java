package ai.devreport.backend.project;

import java.time.Instant;
import java.util.UUID;

import ai.devreport.backend.ai.GenerationRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GenerationController {

	private final GenerationJobService jobs;

	GenerationController(GenerationJobService jobs) {
		this.jobs = jobs;
	}

	@PostMapping("/api/projects/{projectId}/generations")
	@ResponseStatus(HttpStatus.ACCEPTED)
	JobIdResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
		@RequestBody(required = false) GenerationRequest request) {
		GenerationRequest actualRequest = request == null ? new GenerationRequest(null) : request;
		return new JobIdResponse(jobs.create(ProjectController.ownerId(jwt), projectId, actualRequest).getId());
	}

	@GetMapping("/api/generations/{jobId}")
	GenerationJobResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
		return GenerationJobResponse.from(jobs.get(ProjectController.ownerId(jwt), jobId));
	}

	record JobIdResponse(UUID jobId) {
	}

	record GenerationJobResponse(UUID jobId, GenerationJob.Status status, int progress,
		GenerationJob.Stage currentStage, String failureCode, String failureMessage,
		Instant createdAt, Instant startedAt, Instant completedAt) {
		static GenerationJobResponse from(GenerationJob job) {
			return new GenerationJobResponse(job.getId(), job.getStatus(), job.getProgress(),
				job.getCurrentStage(), job.getFailureCode(), job.getFailureMessage(), job.getCreatedAt(),
				job.getStartedAt(), job.getCompletedAt());
		}
	}
}
