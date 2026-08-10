package ai.devreport.backend.report.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

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
		return document != null && schema.validate(document).isEmpty() && hasValidIdsAndTables(document);
	}

	private static boolean hasValidIdsAndTables(JsonNode document) {
		Set<String> sectionIds = new HashSet<>();
		Set<String> blockIds = new HashSet<>();
		for (JsonNode section : document.get("sections")) {
			if (!sectionIds.add(section.get("id").asText())) {
				return false;
			}
			for (JsonNode block : section.get("blocks")) {
				if (!blockIds.add(block.get("id").asText()) || !hasRectangularTable(block)) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean hasRectangularTable(JsonNode block) {
		if (!"table".equals(block.get("type").asText())) {
			return true;
		}
		int columnCount = block.get("columns").size();
		for (JsonNode row : block.get("rows")) {
			if (row.size() != columnCount) {
				return false;
			}
		}
		return true;
	}
}
