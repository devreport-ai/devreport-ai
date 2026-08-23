package ai.devreport.backend.report.api.request;

import java.util.Map;

import tools.jackson.databind.JsonNode;

public record ReportUpdateRequest(JsonNode document, String templateId, Integer templateVersion,
	Map<String, Object> presentationSettings, Long expectedVersion) {
}
