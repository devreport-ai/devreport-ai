package ai.devreport.backend.report.application;

import ai.devreport.backend.report.domain.Report;
import ai.devreport.backend.report.domain.ReportException;
import ai.devreport.backend.report.infrastructure.ReportDocumentSchemaValidator;
import ai.devreport.backend.report.infrastructure.ReportRepository;
import ai.devreport.backend.project.application.ProjectService;
import ai.devreport.backend.upload.application.ProjectFileService;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
	private final ProjectService projects;
	private final ProjectFileService files;
	private final ObjectMapper objectMapper;

	ReportService(ReportRepository reports, ReportDocumentSchemaValidator validator, ProjectService projects,
		ProjectFileService files, ObjectMapper objectMapper) {
		this.reports = reports;
		this.validator = validator;
		this.projects = projects;
		this.files = files;
		this.objectMapper = objectMapper;
	}

	@Transactional(readOnly = true)
	public Report get(UUID ownerId, UUID reportId) {
		return reports.findOwned(reportId, ownerId).orElseThrow(ReportService::notFound);
	}

	public Report update(UUID ownerId, UUID reportId, JsonNode document) {
		Report report = get(ownerId, reportId);
		projects.lock(report.getProjectId());
		requireValid(report.getProjectId(), document);
		report.update(toMap(document));
		return report;
	}

	public Report create(UUID projectId, JsonNode document) {
		projects.lock(projectId);
		requireValid(projectId, document);
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

	private void requireValid(UUID projectId, JsonNode document) {
		if (!validator.isValid(document) || !files.hasAvailableImages(projectId, imageFileIds(document))) {
			throw new ReportException(HttpStatus.BAD_REQUEST, "REPORT_DOCUMENT_INVALID",
				"ReportDocument가 JSON Schema와 프로젝트 파일 참조 규칙을 만족하지 않습니다.");
		}
	}

	private static Set<UUID> imageFileIds(JsonNode document) {
		Set<UUID> fileIds = new HashSet<>();
		for (JsonNode section : document.get("sections")) {
			for (JsonNode block : section.get("blocks")) {
				if ("image".equals(block.get("type").asText())) {
					fileIds.add(UUID.fromString(block.get("fileId").asText()));
				}
			}
		}
		return fileIds;
	}

	private static ReportException notFound() {
		return new ReportException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "보고서를 찾을 수 없습니다.");
	}
}
