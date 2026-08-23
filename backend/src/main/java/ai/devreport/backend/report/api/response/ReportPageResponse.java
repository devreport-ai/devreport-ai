package ai.devreport.backend.report.api.response;

import java.util.List;

import ai.devreport.backend.report.domain.Report;
import org.springframework.data.domain.Page;

public record ReportPageResponse(List<ReportSummaryResponse> items, int page, int size, long totalElements,
	int totalPages) {
	public static ReportPageResponse from(Page<Report> reports) {
		return new ReportPageResponse(reports.getContent().stream().map(ReportSummaryResponse::from).toList(),
			reports.getNumber(), reports.getSize(), reports.getTotalElements(), reports.getTotalPages());
	}
}
