package ai.devreport.backend.report.application;

import ai.devreport.backend.report.domain.Report;
import ai.devreport.backend.report.domain.ReportException;
import ai.devreport.backend.report.infrastructure.ReportDocumentSchemaValidator;
import ai.devreport.backend.report.infrastructure.ReportRepository;
import ai.devreport.backend.project.application.ProjectService;
import ai.devreport.backend.upload.application.ProjectFileService;
import ai.devreport.backend.usage.application.UsageEventService;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class ReportService {

	private static final Pattern TEMPLATE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

	private final ReportRepository reports;
	private final ReportDocumentSchemaValidator validator;
	private final ProjectService projects;
	private final ProjectFileService files;
	private final UsageEventService usageEvents;
	private final ObjectMapper objectMapper;

	ReportService(ReportRepository reports, ReportDocumentSchemaValidator validator, ProjectService projects,
		ProjectFileService files, UsageEventService usageEvents, ObjectMapper objectMapper) {
		this.reports = reports;
		this.validator = validator;
		this.projects = projects;
		this.files = files;
		this.usageEvents = usageEvents;
		this.objectMapper = objectMapper;
	}

	@Transactional(readOnly = true)
	public Report get(UUID ownerId, UUID reportId) {
		return reports.findOwned(reportId, ownerId).orElseThrow(ReportService::notFound);
	}

	@Transactional(readOnly = true)
	public Page<Report> list(UUID ownerId, UUID projectId, int page, int size) {
		projects.requireOwned(ownerId, projectId);
		Sort sort = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
		return reports.findAllByProjectId(projectId, PageRequest.of(page, size, sort));
	}

	public Report update(UUID ownerId, UUID reportId, JsonNode document, String templateId,
		Integer templateVersion, Map<String, Object> presentationSettings, Long expectedVersion) {
		Report report = get(ownerId, reportId);
		projects.lock(report.getProjectId());
		requireExpectedVersion(report, expectedVersion);
		if (!isValidPresentation(templateId, templateVersion, presentationSettings)) {
			throw new ReportException(HttpStatus.BAD_REQUEST, "REPORT_PRESENTATION_INVALID",
				"템플릿과 표현 설정이 올바르지 않습니다.");
		}
		requireValid(report.getProjectId(), document);
		long previousVersion = report.getVersion();
		report.update(toMap(document), templateId, templateVersion, presentationSettings);
		usageEvents.reportEdited(ownerId, report, previousVersion);
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

	private static void requireExpectedVersion(Report report, Long expectedVersion) {
		if (expectedVersion == null || expectedVersion < 0) {
			throw new ReportException(HttpStatus.BAD_REQUEST, "REPORT_VERSION_INVALID",
				"expectedVersion은 0 이상의 정수여야 합니다.");
		}
		if (report.getVersion() != expectedVersion) {
			throw new ReportException(HttpStatus.CONFLICT, "REPORT_VERSION_CONFLICT",
				"보고서가 다른 변경으로 갱신되었습니다.",
				Map.of("expectedVersion", expectedVersion, "currentVersion", report.getVersion()));
		}
	}

	private static boolean isValidPresentation(String templateId, Integer templateVersion,
		Map<String, Object> presentationSettings) {
		if (presentationSettings == null || (templateId == null) != (templateVersion == null)) {
			return false;
		}
		if (templateId != null && (!TEMPLATE_ID.matcher(templateId).matches() || templateVersion < 1)) {
			return false;
		}
		return presentationSettings.entrySet().stream().allMatch(entry ->
			TEMPLATE_ID.matcher(entry.getKey()).matches() && isScalar(entry.getValue()));
	}

	private static boolean isScalar(Object value) {
		return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
	}

	private static ReportException notFound() {
		return new ReportException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "보고서를 찾을 수 없습니다.");
	}
}
