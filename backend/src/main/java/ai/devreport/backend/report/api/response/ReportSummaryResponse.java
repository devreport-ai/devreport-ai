package ai.devreport.backend.report.api.response;

import java.time.Instant;
import java.util.UUID;

import ai.devreport.backend.report.domain.Report;

public record ReportSummaryResponse(UUID id, String templateId, Integer templateVersion, long version,
	Instant updatedAt) {
	public static ReportSummaryResponse from(Report report) {
		return new ReportSummaryResponse(report.getId(), report.getTemplateId(), report.getTemplateVersion(),
			report.getVersion(), report.getUpdatedAt());
	}
}
