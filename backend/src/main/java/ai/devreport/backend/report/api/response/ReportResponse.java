package ai.devreport.backend.report.api.response;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import ai.devreport.backend.report.domain.Report;

public record ReportResponse(UUID id, UUID projectId, Map<String, Object> document, String templateId,
	Integer templateVersion, Map<String, Object> presentationSettings, long version, Instant updatedAt) {
	public static ReportResponse from(Report report) {
		return new ReportResponse(report.getId(), report.getProjectId(), report.getDocument(), report.getTemplateId(),
			report.getTemplateVersion(), report.getPresentationSettings(), report.getVersion(), report.getUpdatedAt());
	}
}
