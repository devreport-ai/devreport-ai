package ai.devreport.backend.integration.ai;

public record AiHealthResponse(
	String status,
	String service,
	String version,
	String env,
	boolean mockReport,
	boolean contractsFound
) {
}
