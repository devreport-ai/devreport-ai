package ai.devreport.backend.generation.api.response;

import java.util.List;
import java.util.Set;

import ai.devreport.backend.integration.ai.AiModelCatalog;
import ai.devreport.backend.integration.ai.AiProvider;

/** 사용자가 생성 시 선택할 수 있는 provider·model 목록과 현재 계정에서 사용 가능한지 여부. */
public record AiModelCatalogResponse(List<AiModelOption> items) {

	public static AiModelCatalogResponse from(List<AiModelCatalog.AiModel> models, Set<AiProvider> registered) {
		return new AiModelCatalogResponse(models.stream()
			.map(model -> new AiModelOption(model.provider(), model.model(), model.label(), model.serverDefault(),
				model.serverDefault() || registered.contains(model.provider())))
			.toList());
	}

	public record AiModelOption(AiProvider provider, String model, String label, boolean serverDefault,
		boolean available) {
	}
}
