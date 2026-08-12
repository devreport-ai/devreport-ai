package ai.devreport.backend.export.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ai.devreport.backend.export.domain.ReportExport;
import ai.devreport.backend.export.domain.ReportExportException;
import ai.devreport.backend.export.domain.ReportExportQueuedEvent;
import ai.devreport.backend.export.infrastructure.PdfReportRenderer;
import ai.devreport.backend.export.infrastructure.ReportExportRepository;
import ai.devreport.backend.report.application.ReportService;
import ai.devreport.backend.report.domain.Report;
import ai.devreport.backend.upload.application.ProjectFileService;
import ai.devreport.backend.upload.application.ProjectFileService.FileContent;
import ai.devreport.backend.usage.application.UsageEventService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class ReportExportService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final ReportExportRepository exports;
	private final ReportService reports;
	private final ProjectFileService files;
	private final ApplicationEventPublisher events;
	private final PdfReportRenderer renderer;
	private final UsageEventService usageEvents;
	private final ObjectMapper objectMapper;
	private final Duration ttl;
	private final Duration renderTokenTtl;

	ReportExportService(ReportExportRepository exports, ReportService reports, ProjectFileService files,
		ApplicationEventPublisher events, PdfReportRenderer renderer, UsageEventService usageEvents,
		ObjectMapper objectMapper, @Value("${storage.export-ttl}") Duration ttl,
		@Value("${export.render-token-ttl}") Duration renderTokenTtl) {
		this.exports = exports;
		this.reports = reports;
		this.files = files;
		this.events = events;
		this.renderer = renderer;
		this.usageEvents = usageEvents;
		this.objectMapper = objectMapper;
		this.ttl = ttl;
		this.renderTokenTtl = renderTokenTtl;
	}

	public ReportExport create(UUID ownerId, UUID reportId) {
		Report report = reports.get(ownerId, reportId);
		if (report.getTemplateId() == null || report.getTemplateVersion() == null) {
			throw new ReportExportException(HttpStatus.CONFLICT, "REPORT_TEMPLATE_NOT_SELECTED",
				"PDF를 생성하려면 보고서 템플릿을 먼저 선택해야 합니다.");
		}
		if (!renderer.isConfigured()) {
			throw new ReportExportException(HttpStatus.SERVICE_UNAVAILABLE, "EXPORT_PRINT_URL_NOT_CONFIGURED",
				"PDF 출력 URL이 설정되지 않았거나 올바르지 않습니다.");
		}
		ReportExport export = exports.save(new ReportExport(report.getId(), snapshot(report)));
		events.publishEvent(new ReportExportQueuedEvent(export.getId()));
		return export;
	}

	public ReportExport get(UUID ownerId, UUID exportId) {
		ReportExport export = exports.findOwned(exportId, ownerId).orElseThrow(ReportExportService::notFound);
		if (export.isExpired() && renderer.delete(exportId)) {
			export.expire();
		}
		return export;
	}

	public RenderData renderData(UUID exportId, String renderToken) {
		ReportExport export = requireRenderAccess(exportId, renderToken);
		Map<String, Object> snapshot = export.getSnapshot();
		return new RenderData(export.getId(), uuid(snapshot.get("reportId")), uuid(snapshot.get("projectId")),
			number(snapshot.get("reportVersion")).longValue(), map(snapshot.get("document")),
			text(snapshot.get("templateId")), number(snapshot.get("templateVersion")).intValue(),
			map(snapshot.get("presentationSettings")), imageFileIds(snapshot.get("document")));
	}

	public FileContent renderFile(UUID exportId, UUID fileId, String renderToken) {
		ReportExport export = requireRenderAccess(exportId, renderToken);
		Map<String, Object> snapshot = export.getSnapshot();
		if (!imageFileIds(snapshot.get("document")).contains(fileId)) {
			throw renderNotFound();
		}
		return files.contentForRender(uuid(snapshot.get("projectId")), fileId);
	}

	public Path download(UUID ownerId, UUID exportId) {
		ReportExport export = get(ownerId, exportId);
		if (export.isExpired()) {
			throw new ReportExportException(HttpStatus.GONE, "EXPORT_EXPIRED", "PDF 다운로드 기간이 만료되었습니다.");
		}
		if (export.getStatus() == ReportExport.Status.FAILED) {
			throw new ReportExportException(HttpStatus.CONFLICT, "EXPORT_FAILED", "PDF 생성에 실패했습니다.");
		}
		if (export.getStatus() != ReportExport.Status.COMPLETED) {
			throw new ReportExportException(HttpStatus.CONFLICT, "EXPORT_NOT_READY", "PDF가 아직 준비되지 않았습니다.");
		}
		Path path = renderer.path(exportId);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new ReportExportException(HttpStatus.NOT_FOUND, "EXPORT_FILE_NOT_FOUND",
				"PDF 파일을 찾을 수 없습니다.");
		}
		return path;
	}

	Optional<ExportInput> start(UUID exportId) {
		Optional<ReportExport> candidate = exports.findForUpdateById(exportId)
			.filter(export -> export.getStatus() == ReportExport.Status.PENDING);
		if (candidate.isEmpty()) {
			return Optional.empty();
		}
		ReportExport export = candidate.get();
		if (export.getSnapshot() == null || export.getSnapshot().isEmpty()) {
			export.fail("EXPORT_SNAPSHOT_MISSING", "PDF 생성에 필요한 보고서 스냅샷이 없습니다.");
			return Optional.empty();
		}
		String renderToken = createRenderToken();
		export.start(hash(renderToken), Instant.now().plus(renderTokenTtl));
		return Optional.of(new ExportInput(export.getId(), renderToken));
	}

	boolean complete(UUID exportId, long size) {
		return exports.findById(exportId)
			.filter(export -> export.getStatus() == ReportExport.Status.PROCESSING)
			.map(export -> {
				export.complete(size, ttl);
				usageEvents.pdfExported(export);
				return true;
			})
			.orElse(false);
	}

	void fail(UUID exportId, String code, String message) {
		exports.findById(exportId).ifPresent(export -> export.fail(code, message));
	}

	@Scheduled(fixedDelayString = "${storage.export-purge-delay:1h}")
	void purgeExpired() {
		// ponytail: 시간당 100개 정리하며, 적체가 관측되면 배치 크기나 실행 주기를 조정한다.
		Sort sort = Sort.by(Sort.Order.asc("expiresAt"), Sort.Order.asc("id"));
		exports.findAllByStatusAndExpiresAtBefore(ReportExport.Status.COMPLETED, Instant.now(),
			PageRequest.of(0, 100, sort)).forEach(export -> {
				if (renderer.delete(export.getId())) {
					export.expire();
				}
			});
	}

	List<UUID> recover() {
		exports.findAllByStatus(ReportExport.Status.PROCESSING).forEach(export -> {
			renderer.delete(export.getId());
			export.fail("EXPORT_INTERRUPTED", "서버 재시작으로 PDF 생성이 중단되었습니다.");
		});
		return exports.findAllByStatus(ReportExport.Status.PENDING).stream().map(ReportExport::getId).toList();
	}

	private ReportExport requireRenderAccess(UUID exportId, String renderToken) {
		String tokenHash = renderToken == null || renderToken.isBlank() ? null : hash(renderToken);
		return exports.findById(exportId)
			.filter(export -> tokenHash != null && export.hasValidRenderToken(tokenHash, Instant.now()))
			.orElseThrow(ReportExportService::renderNotFound);
	}

	private Map<String, Object> snapshot(Report report) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("reportId", report.getId().toString());
		snapshot.put("projectId", report.getProjectId().toString());
		snapshot.put("reportVersion", report.getVersion());
		snapshot.put("document", copy(report.getDocument()));
		snapshot.put("templateId", report.getTemplateId());
		snapshot.put("templateVersion", report.getTemplateVersion());
		snapshot.put("presentationSettings", copy(report.getPresentationSettings()));
		return snapshot;
	}

	private <T> T copy(T value) {
		return objectMapper.convertValue(value, new TypeReference<>() {
		});
	}

	private static String createRenderToken() {
		byte[] token = new byte[32];
		RANDOM.nextBytes(token);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
	}

	private static String hash(String token) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	private static Set<UUID> imageFileIds(Object value) {
		Set<UUID> fileIds = new HashSet<>();
		collectImageFileIds(value, fileIds);
		return fileIds;
	}

	private static void collectImageFileIds(Object value, Set<UUID> fileIds) {
		if (value instanceof Map<?, ?> map) {
			if ("image".equals(map.get("type")) && map.get("fileId") != null) {
				try {
					fileIds.add(UUID.fromString(map.get("fileId").toString()));
				} catch (IllegalArgumentException ignored) {
					// The report schema validator rejects this before an export is created.
				}
			}
			map.values().forEach(item -> collectImageFileIds(item, fileIds));
		} else if (value instanceof Iterable<?> values) {
			values.forEach(item -> collectImageFileIds(item, fileIds));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private static Number number(Object value) {
		return value instanceof Number number ? number : 0;
	}

	private static UUID uuid(Object value) {
		return UUID.fromString(text(value));
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString();
	}

	private static ReportExportException notFound() {
		return new ReportExportException(HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND",
			"PDF 내보내기 작업을 찾을 수 없습니다.");
	}

	private static ReportExportException renderNotFound() {
		return new ReportExportException(HttpStatus.NOT_FOUND, "EXPORT_RENDER_NOT_FOUND",
			"PDF 출력 리소스를 찾을 수 없습니다.");
	}

	record ExportInput(UUID exportId, String renderToken) {
	}

	public record RenderData(UUID exportId, UUID reportId, UUID projectId, long reportVersion,
		Map<String, Object> document, String templateId, Integer templateVersion,
		Map<String, Object> presentationSettings, Set<UUID> imageFileIds) {
	}
}
