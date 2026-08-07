package ai.devreport.backend.ai;

public record AiHealthResponse(
	String status,
	String service,
	String version,
	String env,
	boolean mockReport,
	boolean contractsFound
) {
}
