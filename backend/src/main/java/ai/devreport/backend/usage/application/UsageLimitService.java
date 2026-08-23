package ai.devreport.backend.usage.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ai.devreport.backend.auth.infrastructure.UserRepository;
import ai.devreport.backend.export.domain.ReportExport;
import ai.devreport.backend.export.infrastructure.ReportExportRepository;
import ai.devreport.backend.generation.domain.GenerationJob;
import ai.devreport.backend.generation.infrastructure.GenerationJobRepository;
import ai.devreport.backend.upload.infrastructure.UploadedFileRepository;
import ai.devreport.backend.usage.domain.UsageLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UsageLimitService {

	private static final Set<GenerationJob.Status> ACTIVE_GENERATIONS =
		EnumSet.of(GenerationJob.Status.PENDING, GenerationJob.Status.PROCESSING);
	private static final Set<ReportExport.Status> ACTIVE_EXPORTS =
		EnumSet.of(ReportExport.Status.PENDING, ReportExport.Status.PROCESSING);

	private final UserRepository users;
	private final UploadedFileRepository files;
	private final GenerationJobRepository generations;
	private final ReportExportRepository exports;
	private final UsageLimitProperties properties;
	private final ZoneId dayZone;

	UsageLimitService(UserRepository users, UploadedFileRepository files, GenerationJobRepository generations,
		ReportExportRepository exports, UsageLimitProperties properties) {
		this.users = users;
		this.files = files;
		this.generations = generations;
		this.exports = exports;
		this.properties = properties;
		this.dayZone = ZoneId.of(properties.getDayZone());
	}

	public void checkUpload(UUID userId, long requestedBytes) {
		lockUser(userId);
		long currentFiles = files.countByOwnerId(userId);
		if (currentFiles >= properties.getUpload().getMaxFiles()) {
			throw limit(HttpStatus.CONTENT_TOO_LARGE, "UPLOAD_FILE_QUOTA_EXCEEDED",
				"사용자 파일 개수 한도를 초과했습니다.",
				Map.of("maxFiles", properties.getUpload().getMaxFiles(), "currentFiles", currentFiles));
		}
		long currentBytes = files.sumSizeByOwnerId(userId);
		long maxBytes = properties.getUpload().getMaxTotalBytes();
		if (requestedBytes < 0 || currentBytes > maxBytes - requestedBytes) {
			throw limit(HttpStatus.CONTENT_TOO_LARGE, "UPLOAD_STORAGE_QUOTA_EXCEEDED",
				"사용자 업로드 저장 용량 한도를 초과했습니다.",
				Map.of("maxBytes", maxBytes, "currentBytes", currentBytes, "requestedBytes", requestedBytes));
		}
	}

	/**
	 * @param usesServerKey 서버 기본 키로 실행하는 요청이면 일일 한도를 적용한다.
	 *                      사용자 키(BYOK) 요청은 비용이 사용자 계정에 청구되므로 일일 한도 대신 동시 실행 한도만 적용한다.
	 */
	public void checkGeneration(UUID userId, boolean usesServerKey) {
		lockUser(userId);
		if (usesServerKey) {
			Instant dayStart = dayStart();
			long dailyCount = generations.countServerKeyByOwnerIdAndCreatedAtOnOrAfter(userId, dayStart);
			long dailyLimit = properties.getGeneration().getDailyLimit();
			if (dailyCount >= dailyLimit) {
				throw limit(HttpStatus.TOO_MANY_REQUESTS, "GENERATION_DAILY_LIMIT_EXCEEDED",
					"오늘의 보고서 생성 한도를 초과했습니다.",
					Map.of("dailyLimit", dailyLimit, "currentCount", dailyCount));
			}
		}
		long activeCount = generations.countByOwnerIdAndStatusIn(userId, ACTIVE_GENERATIONS);
		long concurrentLimit = properties.getGeneration().getConcurrentLimit();
		if (activeCount >= concurrentLimit) {
			throw limit(HttpStatus.TOO_MANY_REQUESTS, "GENERATION_CONCURRENCY_LIMIT_EXCEEDED",
				"동시에 실행할 수 있는 보고서 생성 작업 수를 초과했습니다.",
				Map.of("concurrentLimit", concurrentLimit, "currentCount", activeCount));
		}
	}

	public void checkExport(UUID userId) {
		lockUser(userId);
		Instant dayStart = dayStart();
		long dailyCount = exports.countByOwnerIdAndCreatedAtOnOrAfter(userId, dayStart);
		long dailyLimit = properties.getExport().getDailyLimit();
		if (dailyCount >= dailyLimit) {
			throw limit(HttpStatus.TOO_MANY_REQUESTS, "PDF_DAILY_LIMIT_EXCEEDED",
				"오늘의 PDF 생성 한도를 초과했습니다.",
				Map.of("dailyLimit", dailyLimit, "currentCount", dailyCount));
		}
		long activeCount = exports.countByOwnerIdAndStatusIn(userId, ACTIVE_EXPORTS);
		long concurrentLimit = properties.getExport().getConcurrentLimit();
		if (activeCount >= concurrentLimit) {
			throw limit(HttpStatus.TOO_MANY_REQUESTS, "PDF_CONCURRENCY_LIMIT_EXCEEDED",
				"동시에 실행할 수 있는 PDF 생성 작업 수를 초과했습니다.",
				Map.of("concurrentLimit", concurrentLimit, "currentCount", activeCount));
		}
	}

	/** 사용자 행 잠금. 한도 검사·키 삭제·생성 접수를 같은 잠금으로 직렬화한다. */
	public void lockUser(UUID userId) {
		users.findForUpdate(userId).orElseThrow(() -> new IllegalStateException("Authenticated user is missing"));
	}

	private Instant dayStart() {
		return LocalDate.now(dayZone).atStartOfDay(dayZone).toInstant();
	}

	private static UsageLimitException limit(HttpStatus status, String code, String message, Object details) {
		return new UsageLimitException(status, code, message, details);
	}
}
