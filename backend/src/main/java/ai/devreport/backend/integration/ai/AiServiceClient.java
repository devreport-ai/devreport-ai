package ai.devreport.backend.integration.ai;

import ai.devreport.backend.report.domain.ReportDocument;

public interface AiServiceClient {

	AiHealthResponse health();

	/**
	 * @param providerApiKey 사용자가 등록한 provider API Key. null이면 AI Service의 서버 키로 실행한다.
	 *                       헤더로만 전달하며 요청 본문·로그에 싣지 않는다.
	 */
	ReportDocument generate(GenerationRequest request, GenerationBundle bundle, String providerApiKey);

	/** provider API로 키 유효성을 확인한다. 잘못된 키는 AI_CREDENTIAL_INVALID 코드의 예외로 알린다. */
	void verifyCredential(AiProvider provider, String providerApiKey);
}
