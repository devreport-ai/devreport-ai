package ai.devreport.backend.ai;

public interface AiServiceClient {

	AiHealthResponse health();

	ReportDocument generate(GenerationRequest request);
}
