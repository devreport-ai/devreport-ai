package ai.devreport.backend.report.domain;

import java.util.List;
import java.util.Map;

public record ReportDocument(Metadata metadata, List<Section> sections) {

	public record Metadata(String title, String author, String course, String date) {
	}

	public record Section(String id, String title, List<Map<String, Object>> blocks) {
	}
}
