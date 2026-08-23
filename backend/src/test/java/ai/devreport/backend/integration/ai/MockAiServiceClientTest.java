package ai.devreport.backend.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.devreport.backend.report.domain.ReportDocument;
import org.junit.jupiter.api.Test;

class MockAiServiceClientTest {

	private final MockAiServiceClient client = new MockAiServiceClient();

	@Test
	void returnsHealthyStatusAndSchemaShapedReport() {
		assertThat(client.health().status()).isEqualTo("UP");
		ReportDocument report = client.generate(
			new GenerationRequest(List.of(UUID.randomUUID()), Map.of(), "테스트", null, null),
			new GenerationBundle(Path.of("."), Path.of("manifest.json"), List.of()), null);
		assertThat(report.metadata().title()).isEqualTo("Spring Boot 실습보고서");
		assertThat(report.sections().getFirst().blocks())
			.allSatisfy(block -> assertThat(block).containsKeys("id", "type"));
	}
}
