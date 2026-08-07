package ai.devreport.backend.integration.ai;

import ai.devreport.backend.report.domain.ReportDocument;

public record GenerationRequest(ReportDocument document) {
}
