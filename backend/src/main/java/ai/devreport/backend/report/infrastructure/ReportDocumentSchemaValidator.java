package ai.devreport.backend.report.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ReportDocumentSchemaValidator {

	private final Schema schema;

	ReportDocumentSchemaValidator() throws IOException {
		String schemaDocument = new ClassPathResource("report-document.schema.json")
			.getContentAsString(StandardCharsets.UTF_8);
		this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
			.getSchema(schemaDocument, InputFormat.JSON);
	}

	public boolean isValid(JsonNode document) {
		return document != null && schema.validate(document).isEmpty();
	}
}
