package ai.devreport.backend.integration.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 보고서 생성 요청. provider·model은 선택이며 둘 다 비우면 서버 기본 모델을 사용한다.
 * Backend는 접수 시 allowlist로 검증한 값을 채워 GenerationJob에 snapshot으로 보존하고 AI Service에 그대로 전달한다.
 * 사용자 API Key는 이 객체에 절대 담지 않는다(JSONB로 저장되고 로그·AI 요청 본문에 실린다).
 */
public record GenerationRequest(
	@NotEmpty List<@NotNull UUID> fileIds,
	@NotNull Map<String, Object> metadata,
	@NotBlank String instructions,
	AiProvider provider,
	@Size(max = 100) String model
) {

	public GenerationRequest withModel(AiProvider resolvedProvider, String resolvedModel) {
		return new GenerationRequest(fileIds, metadata, instructions, resolvedProvider, resolvedModel);
	}
}
