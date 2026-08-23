package ai.devreport.backend.generation.api.response;

import java.util.List;
import java.util.Set;

import ai.devreport.backend.integration.ai.AiModelCatalog;
import ai.devreport.backend.integration.ai.AiProvider;

/**
 * 사용자가 생성 시 선택할 수 있는 provider·model 목록과 현재 계정에서 사용 가능한지 여부.
 * usesUserKey는 이 모델을 고르면 사용자 키로 실행(=사용자 계정 과금)된다는 뜻이며, 기본 모델이라도 키가 있으면 true다.
 */
public record AiModelCatalogResponse(List<AiModelOption> items) {

	public static AiModelCatalogResponse from(List<AiModelCatalog.AiModel> models, Set<AiProvider> usableProviders) {
		return new AiModelCatalogResponse(models.stream()
			.map(model -> {
				boolean usesUserKey = usableProviders.contains(model.provider());
				return new AiModelOption(model.provider(), model.model(), model.label(), model.serverDefault(),
					model.serverDefault() || usesUserKey, usesUserKey);
			})
			.toList());
	}

	public record AiModelOption(AiProvider provider, String model, String label, boolean serverDefault,
		boolean available, boolean usesUserKey) {
	}
}
