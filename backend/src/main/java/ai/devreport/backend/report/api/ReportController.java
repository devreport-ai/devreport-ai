package ai.devreport.backend.report.api;

import java.util.UUID;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.report.api.request.ReportUpdateRequest;
import ai.devreport.backend.report.api.response.ReportResponse;
import ai.devreport.backend.report.application.ReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
class ReportController {

	private final ReportService reports;

	ReportController(ReportService reports) {
		this.reports = reports;
	}

	@GetMapping("/{reportId}")
	ResponseEntity<ReportResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId) {
		return ResponseEntity.ok(ReportResponse.from(reports.get(AuthenticatedUser.id(jwt), reportId)));
	}

	@PutMapping("/{reportId}")
	ResponseEntity<ReportResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId,
		@RequestBody ReportUpdateRequest request) {
		return ResponseEntity.ok(ReportResponse.from(reports.update(AuthenticatedUser.id(jwt), reportId,
			request.document(), request.templateId(), request.templateVersion(), request.presentationSettings(),
			request.expectedVersion())));
	}
}
