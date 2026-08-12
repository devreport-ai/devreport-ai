package ai.devreport.backend.report.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.report.application.ReportService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/reports")
class ReportController {

	private final ReportService reports;

	ReportController(ReportService reports) {
		this.reports = reports;
	}

	@GetMapping("/{reportId}")
	ReportResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId) {
		return ReportResponse.from(reports.get(AuthenticatedUser.id(jwt), reportId));
	}

	@PutMapping("/{reportId}")
	ReportResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId,
		@RequestBody ReportUpdateRequest request) {
		return ReportResponse.from(reports.update(AuthenticatedUser.id(jwt), reportId, request.document(),
			request.templateId(), request.templateVersion(), request.presentationSettings(), request.expectedVersion()));
	}

	record ReportUpdateRequest(JsonNode document, String templateId, Integer templateVersion,
		Map<String, Object> presentationSettings, Long expectedVersion) {
	}

	record ReportResponse(UUID id, Map<String, Object> document, String templateId, Integer templateVersion,
		Map<String, Object> presentationSettings, long version, Instant updatedAt) {
		static ReportResponse from(ai.devreport.backend.report.domain.Report report) {
			return new ReportResponse(report.getId(), report.getDocument(), report.getTemplateId(),
				report.getTemplateVersion(), report.getPresentationSettings(), report.getVersion(), report.getUpdatedAt());
		}
	}
}
