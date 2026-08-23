package ai.devreport.backend.upload.domain;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** AI 분석에 허용하는 문서·소스 확장자와 전송 MIME의 단일 기준. */
public final class AnalysisFileTypes {

	public static final Set<String> SOURCE_EXTENSIONS = Set.of(
		"java", "kt", "py", "js", "jsx", "ts", "tsx", "html", "css", "scss", "sql", "xml",
		"json", "yaml", "yml", "gradle", "properties", "toml", "go", "rs", "c", "h", "cpp", "hpp",
		"cs", "php", "rb", "swift", "dart", "vue", "svelte"
	);
	public static final Set<String> DOCUMENT_EXTENSIONS = Set.of("md", "txt", "docx");

	private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
		Map.entry("pdf", "application/pdf"),
		Map.entry("json", "application/json"), Map.entry("xml", "application/xml"),
		Map.entry("html", "text/html"), Map.entry("css", "text/css"),
		Map.entry("scss", "text/x-scss"), Map.entry("js", "text/javascript"),
		Map.entry("jsx", "text/jsx"), Map.entry("ts", "text/typescript"),
		Map.entry("tsx", "text/tsx"), Map.entry("md", "text/markdown"),
		Map.entry("yaml", "application/yaml"), Map.entry("yml", "application/yaml"),
		Map.entry("sql", "application/sql"), Map.entry("toml", "application/toml"),
		Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
	);

	private AnalysisFileTypes() {
	}

	public static boolean isSource(String extension) {
		return SOURCE_EXTENSIONS.contains(extension);
	}

	public static boolean isDocument(String extension) {
		return DOCUMENT_EXTENSIONS.contains(extension);
	}

	public static boolean isAnalyzable(String extension) {
		return isSource(extension) || isDocument(extension);
	}

	public static String contentType(String extension) {
		return CONTENT_TYPES.getOrDefault(extension, "text/plain");
	}

	public static String extension(Path path) {
		return extension(path.getFileName().toString());
	}

	public static String extension(String name) {
		int separator = name.lastIndexOf('.');
		return separator < 0 ? "" : name.substring(separator + 1).toLowerCase(Locale.ROOT);
	}
}
