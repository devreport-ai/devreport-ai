package ai.devreport.backend.report;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class ReportService {

	private final ReportRepository reports;
	private final ReportDocumentSchemaValidator validator;
	private final ObjectMapper objectMapper;

	ReportService(ReportRepository reports, ReportDocumentSchemaValidator validator, ObjectMapper objectMapper) {
		this.reports = reports;
		this.validator = validator;
		this.objectMapper = objectMapper;
	}

	@Transactional(readOnly = true)
	public Report get(UUID ownerId, UUID reportId) {
		return reports.findOwned(reportId, ownerId).orElseThrow(ReportService::notFound);
	}

	Report update(UUID ownerId, UUID reportId, JsonNode document) {
		Report report = get(ownerId, reportId);
		requireValid(document);
		report.update(toMap(document));
		return report;
	}

	public Report create(UUID projectId, JsonNode document) {
		requireValid(document);
		return reports.save(new Report(projectId, toMap(document)));
	}

	@Transactional(readOnly = true)
	public Optional<Map<String, Object>> findDocument(UUID reportId) {
		return reports.findById(reportId).map(Report::getDocument);
	}

	private Map<String, Object> toMap(JsonNode document) {
		return objectMapper.convertValue(document, new TypeReference<>() {
		});
	}

	private void requireValid(JsonNode document) {
		if (!validator.isValid(document)) {
			throw new ReportException(HttpStatus.BAD_REQUEST, "REPORT_DOCUMENT_INVALID",
				"ReportDocument가 JSON Schema와 일치하지 않습니다.");
		}
	}

	private static ReportException notFound() {
		return new ReportException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "보고서를 찾을 수 없습니다.");
	}
}
