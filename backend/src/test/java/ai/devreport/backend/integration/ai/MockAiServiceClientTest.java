package ai.devreport.backend.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devreport.backend.report.domain.ReportDocument;
import org.junit.jupiter.api.Test;

class MockAiServiceClientTest {

	private final MockAiServiceClient client = new MockAiServiceClient();

	@Test
	void returnsHealthyStatusAndSchemaShapedReport() {
		assertThat(client.health().status()).isEqualTo("UP");
		ReportDocument report = client.generate(new GenerationRequest(null));
		assertThat(report.metadata().title()).isEqualTo("Spring Boot 실습보고서");
		assertThat(report.sections().getFirst().blocks())
			.allSatisfy(block -> assertThat(block).containsKeys("id", "type"));
	}
}
