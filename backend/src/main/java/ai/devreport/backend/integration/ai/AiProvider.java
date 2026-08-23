package ai.devreport.backend.integration.ai;

/**
 * 보고서 생성에 사용할 수 있는 LLM provider.
 * 사용자 API Key와 모델 allowlist는 provider 단위로 관리한다.
 */
public enum AiProvider {
	GEMINI,
	ANTHROPIC
}
