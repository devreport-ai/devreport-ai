package ai.devreport.backend.report.api;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.report.api.response.ReportPageResponse;
import ai.devreport.backend.report.application.ReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProjectReportController {

	private final ReportService reports;

	ProjectReportController(ReportService reports) {
		this.reports = reports;
	}

	@GetMapping("/api/projects/{projectId}/reports")
	ResponseEntity<ReportPageResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ResponseEntity.ok(
			ReportPageResponse.from(reports.list(AuthenticatedUser.id(jwt), projectId, page, size)));
	}
}
