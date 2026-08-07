package ai.devreport.backend.integration.ai;

import ai.devreport.backend.report.ReportDocument;

public interface AiServiceClient {

	AiHealthResponse health();

	ReportDocument generate(GenerationRequest request);
}
