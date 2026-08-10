package ai.devreport.backend.report.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReportDocumentSchemaValidatorTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final ReportDocumentSchemaValidator validator = new ReportDocumentSchemaValidator();

	ReportDocumentSchemaValidatorTest() throws Exception {
	}

	@Test
	void validatesSampleIdsAndRectangularTables() throws Exception {
		assertThat(validator.isValid(objectMapper.readTree(
			Path.of("../contracts/examples/sample-report.json").toFile()))).isTrue();
		assertThat(validator.isValid(objectMapper.readTree("""
			{"metadata":{"title":"중복"},"sections":[{"id":"section","title":"섹션","blocks":[
			{"id":"same","type":"paragraph","content":"첫째"},
			{"id":"same","type":"paragraph","content":"둘째"}]}]}
			"""))).isFalse();
		assertThat(validator.isValid(objectMapper.readTree("""
			{"metadata":{"title":"표"},"sections":[{"id":"section","title":"섹션","blocks":[
			{"id":"table","type":"table","columns":["A","B"],"rows":[["1"]]}]}]}
			"""))).isFalse();
	}
}
