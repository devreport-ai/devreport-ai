package ai.devreport.backend.integration.ai;

import java.util.List;
import java.util.Map;

import ai.devreport.backend.report.domain.ReportDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ai.service.mock", havingValue = "true")
public class MockAiServiceClient implements AiServiceClient {

	@Override
	public AiHealthResponse health() {
		return new AiHealthResponse("UP", "devreport-ai-service", "mock", "local", true, true);
	}

	@Override
	public ReportDocument generate(GenerationRequest request, GenerationBundle bundle) {
		return new ReportDocument(
			new ReportDocument.Metadata("Spring Boot 실습보고서", "김예찬", "SKALA Backend", "2026-08-06"),
			List.of(new ReportDocument.Section("overview", "1. 프로젝트 개요", List.of(
				Map.of("id", "overview-summary", "type", "paragraph", "content",
					"본 실습에서는 REST API를 구현하였다.")
			)))
		);
	}
}
