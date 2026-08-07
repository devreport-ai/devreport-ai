package ai.devreport.backend.integration.ai;

import ai.devreport.backend.report.ReportDocument;

public record GenerationRequest(ReportDocument document) {
}
