package ai.devreport.backend.integration.ai;

import java.util.List;
import java.util.Map;

import ai.devreport.backend.report.domain.ReportDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ai.service.mock", havingValue = "true")
public class MockAiServiceClient implements AiServiceClient {

	@Override
	public AiHealthResponse health() {
		return new AiHealthResponse("UP", "devreport-ai-service", "mock", "local", true, true);
	}

	@Override
	public void verifyCredential(AiProvider provider, String providerApiKey) {
		// 로컬 검증용: "invalid"로 시작하는 키만 거부해 실패 흐름을 확인할 수 있게 한다.
		if (providerApiKey == null || providerApiKey.startsWith("invalid")) {
			throw new AiServiceException(HttpStatus.BAD_REQUEST, "AI_CREDENTIAL_INVALID",
				"API Key가 올바르지 않습니다.", null);
		}
	}

	@Override
	public ReportDocument generate(GenerationRequest request, GenerationBundle bundle, String providerApiKey) {
		return new ReportDocument(
			new ReportDocument.Metadata("Spring Boot 실습보고서", "김예찬", "SKALA Backend", "2026-08-06"),
			List.of(new ReportDocument.Section("overview", "1. 프로젝트 개요", List.of(
				Map.of("id", "overview-summary", "type", "paragraph", "content",
					"본 실습에서는 REST API를 구현하였다.")
			)))
		);
	}
}
