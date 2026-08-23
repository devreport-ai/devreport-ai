package ai.devreport.backend.integration.ai;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** provider·model allowlist 조회. 임의 모델 ID 입력은 허용하지 않는다. */
@Component
public class AiModelCatalog {

	private final List<AiModel> models;
	private final AiModel serverDefault;

	AiModelCatalog(AiModelProperties properties) {
		this.models = properties.getAllowlist().stream()
			.map(entry -> new AiModel(entry.getProvider(), entry.getModel(), entry.getLabel(), entry.isServerDefault()))
			.toList();
		this.serverDefault = models.stream().filter(AiModel::serverDefault).findFirst()
			.orElseThrow(() -> new IllegalStateException("ai.models.allowlist에 서버 기본 모델이 없습니다."));
	}

	public List<AiModel> models() {
		return models;
	}

	public AiModel serverDefault() {
		return serverDefault;
	}

	public Optional<AiModel> find(AiProvider provider, String model) {
		return models.stream()
			.filter(candidate -> candidate.provider() == provider && candidate.model().equals(model))
			.findFirst();
	}

	public record AiModel(AiProvider provider, String model, String label, boolean serverDefault) {
	}
}
