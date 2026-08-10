package ai.devreport.backend.integration.ai;

import ai.devreport.backend.upload.domain.UploadedFile;
import ai.devreport.backend.upload.infrastructure.UploadedFileRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class GenerationBundleFactory {

	private static final Logger log = LoggerFactory.getLogger(GenerationBundleFactory.class);
	private static final int MAX_FILE_COUNT = 1_000;
	private static final long MAX_TOTAL_SIZE = 100L * 1024 * 1024;
	private static final Map<String, String> SOURCE_CONTENT_TYPES = Map.ofEntries(
		Map.entry("json", "application/json"), Map.entry("xml", "application/xml"),
		Map.entry("html", "text/html"), Map.entry("css", "text/css"),
		Map.entry("scss", "text/x-scss"), Map.entry("js", "text/javascript"),
		Map.entry("jsx", "text/jsx"), Map.entry("ts", "text/typescript"),
		Map.entry("tsx", "text/tsx"), Map.entry("md", "text/markdown"),
		Map.entry("yaml", "application/yaml"), Map.entry("yml", "application/yaml"),
		Map.entry("sql", "application/sql"), Map.entry("toml", "application/toml")
	);

	private final UploadedFileRepository uploadedFiles;
	private final ObjectMapper objectMapper;
	private final Path uploadRoot;

	GenerationBundleFactory(UploadedFileRepository uploadedFiles, ObjectMapper objectMapper,
		@Value("${storage.upload-path}") String uploadPath) {
		this.uploadedFiles = uploadedFiles;
		this.objectMapper = objectMapper;
		this.uploadRoot = Path.of(uploadPath).toAbsolutePath().normalize();
	}

	public GenerationBundle create(GenerationRequest request) {
		Path root = null;
		try {
			root = Files.createTempDirectory("devreport-generation-");
			Files.createDirectories(root.resolve("source"));
			Files.createDirectories(root.resolve("documents"));
			Files.createDirectories(root.resolve("images"));

			List<GenerationBundle.FilePart> parts = new ArrayList<>();
			List<ManifestFile> manifestFiles = new ArrayList<>();
			for (UUID fileId : request.fileIds()) {
				UploadedFile file = uploadedFiles.findById(fileId)
					.orElseThrow(() -> new IllegalStateException("Selected generation file is missing"));
				addFile(root, file, parts, manifestFiles);
			}
			Path manifest = root.resolve("manifest.json");
			objectMapper.writeValue(manifest.toFile(), new Manifest(1, manifestFiles));
			return new GenerationBundle(root, manifest, List.copyOf(parts));
		} catch (IOException | RuntimeException exception) {
			deleteQuietly(root);
			if (exception instanceof IOException ioException) {
				throw new UncheckedIOException("Failed to create generation bundle", ioException);
			}
			throw (RuntimeException) exception;
		}
	}

	private void addFile(Path root, UploadedFile file, List<GenerationBundle.FilePart> parts,
		List<ManifestFile> manifestFiles) throws IOException {
		String extension = extension(file.getOriginalName());
		if (extension.equals("zip")) {
			Path extracted = storedPath(file).resolveSibling(file.getStoredName() + ".extracted");
			try (var paths = Files.walk(extracted)) {
				for (Path source : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					.sorted().toList()) {
					Path relative = extracted.relativize(source);
					copy(root, source, "source/" + file.getId() + "/" + portable(relative), file,
						sourceContentType(source), parts, manifestFiles);
				}
			}
		} else if (extension.equals("md") || extension.equals("txt")) {
			copy(root, storedPath(file), "documents/" + file.getId() + "/" + file.getOriginalName(), file,
				file.getContentType(), parts, manifestFiles);
		} else if (extension.equals("png") || extension.equals("jpg") || extension.equals("jpeg")) {
			copy(root, storedPath(file), "images/" + file.getId() + "/" + file.getOriginalName(), file,
				file.getContentType(), parts, manifestFiles);
		}
	}

	private static void copy(Path root, Path source, String relativePath, UploadedFile uploadedFile,
		String contentType, List<GenerationBundle.FilePart> parts, List<ManifestFile> manifestFiles)
		throws IOException {
		if (parts.size() >= MAX_FILE_COUNT) {
			throw new IllegalStateException("Generation bundle exceeds 1,000 files");
		}
		Path target = root.resolve(relativePath);
		Files.createDirectories(target.getParent());
		try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			Files.copy(input, target);
		}
		long size = Files.size(target);
		long totalSize = manifestFiles.stream().mapToLong(ManifestFile::size).sum() + size;
		if (totalSize > MAX_TOTAL_SIZE) {
			throw new IllegalStateException("Generation bundle exceeds 100 MiB");
		}
		parts.add(new GenerationBundle.FilePart(target, relativePath, contentType));
		manifestFiles.add(new ManifestFile(uploadedFile.getId(), relativePath.substring(0,
			relativePath.indexOf('/')), relativePath, contentType, size));
	}

	private Path storedPath(UploadedFile file) {
		return uploadRoot.resolve(file.getProjectId().toString()).resolve(file.getStoredName());
	}

	private static String sourceContentType(Path path) {
		return SOURCE_CONTENT_TYPES.getOrDefault(extension(path.getFileName().toString()), "text/plain");
	}

	private static String extension(String name) {
		int separator = name.lastIndexOf('.');
		return separator < 0 ? "" : name.substring(separator + 1).toLowerCase(Locale.ROOT);
	}

	private static String portable(Path path) {
		StringBuilder result = new StringBuilder();
		for (Path part : path) {
			if (!result.isEmpty()) {
				result.append('/');
			}
			result.append(part);
		}
		return result.toString();
	}

	private static void deleteQuietly(Path root) {
		if (root == null || Files.notExists(root)) {
			return;
		}
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException exception) {
			log.warn("Failed to clean up incomplete generation bundle: {}", root, exception);
		}
	}

	private record Manifest(int version, List<ManifestFile> files) {
	}

	private record ManifestFile(UUID fileId, String category, String path, String mimeType, long size) {
	}
}
