package ai.devreport.backend.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MockAiServiceClientTest {

	private final MockAiServiceClient client = new MockAiServiceClient();

	@Test
	void returnsHealthyStatusAndContractSampleReport() throws Exception {
		ReportDocument contractSample = new ObjectMapper().readValue(
			Path.of("../contracts/examples/sample-report.json").toFile(), ReportDocument.class);

		assertThat(client.health().status()).isEqualTo("UP");
		assertThat(client.sampleReport()).isEqualTo(contractSample);
	}
}
