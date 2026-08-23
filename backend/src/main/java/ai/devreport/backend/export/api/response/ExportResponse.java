package ai.devreport.backend.export.api.response;

import java.time.Instant;
import java.util.UUID;

import ai.devreport.backend.export.domain.ReportExport;

public record ExportResponse(UUID exportId, UUID reportId, String status, Long size, String failureCode,
	String failureMessage, Instant expiresAt, Instant createdAt, Instant startedAt, Instant completedAt) {

	public static ExportResponse from(ReportExport export) {
		String status = export.isExpired() ? "EXPIRED" : export.getStatus().name();
		return new ExportResponse(export.getId(), export.getReportId(), status, export.getSize(),
			export.getFailureCode(), export.getFailureMessage(), export.getExpiresAt(), export.getCreatedAt(),
			export.getStartedAt(), export.getCompletedAt());
	}
}
