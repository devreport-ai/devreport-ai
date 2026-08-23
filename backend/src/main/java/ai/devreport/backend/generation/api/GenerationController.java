package ai.devreport.backend.generation.api;

import java.util.UUID;

import jakarta.validation.Valid;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.generation.api.response.GenerationJobResponse;
import ai.devreport.backend.generation.api.response.JobIdResponse;
import ai.devreport.backend.generation.application.GenerationJobService;
import ai.devreport.backend.integration.ai.GenerationRequest;
import ai.devreport.backend.usage.application.RateLimitService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GenerationController {

	private final GenerationJobService jobs;
	private final RateLimitService rateLimits;

	GenerationController(GenerationJobService jobs, RateLimitService rateLimits) {
		this.jobs = jobs;
		this.rateLimits = rateLimits;
	}

	@PostMapping("/api/projects/{projectId}/generations")
	ResponseEntity<JobIdResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
		@Valid @RequestBody GenerationRequest request) {
		UUID ownerId = AuthenticatedUser.id(jwt);
		rateLimits.checkGeneration(ownerId);
		UUID jobId = jobs.create(ownerId, projectId, request).getId();
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(new JobIdResponse(jobId));
	}

	@GetMapping("/api/generations/{jobId}")
	ResponseEntity<GenerationJobResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
		return ResponseEntity.ok(GenerationJobResponse.from(jobs.get(AuthenticatedUser.id(jwt), jobId)));
	}

	@DeleteMapping("/api/generations/{jobId}")
	ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
		jobs.cancel(AuthenticatedUser.id(jwt), jobId);
		return ResponseEntity.noContent().build();
	}
}
