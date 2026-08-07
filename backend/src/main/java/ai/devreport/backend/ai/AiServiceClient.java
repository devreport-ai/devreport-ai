package ai.devreport.backend.ai;

import ai.devreport.backend.report.ReportDocument;

public interface AiServiceClient {

	AiHealthResponse health();

	ReportDocument generate(GenerationRequest request);
}
