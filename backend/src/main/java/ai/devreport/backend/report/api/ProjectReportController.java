package ai.devreport.backend.report.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.report.application.ReportService;
import ai.devreport.backend.report.domain.Report;
import org.springframework.data.domain.Page;
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
	ReportPageResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ReportPageResponse.from(reports.list(AuthenticatedUser.id(jwt), projectId, page, size));
	}

	record ReportPageResponse(List<ReportSummaryResponse> items, int page, int size, long totalElements,
		int totalPages) {
		static ReportPageResponse from(Page<Report> reports) {
			return new ReportPageResponse(reports.getContent().stream().map(ReportSummaryResponse::from).toList(),
				reports.getNumber(), reports.getSize(), reports.getTotalElements(), reports.getTotalPages());
		}
	}

	record ReportSummaryResponse(UUID id, String templateId, Integer templateVersion, long version,
		Instant updatedAt) {
		static ReportSummaryResponse from(Report report) {
			return new ReportSummaryResponse(report.getId(), report.getTemplateId(), report.getTemplateVersion(),
				report.getVersion(), report.getUpdatedAt());
		}
	}
}
