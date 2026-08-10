package ai.devreport.backend.integration.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public record GenerationBundle(Path root, Path manifest, List<FilePart> files) implements AutoCloseable {

	@Override
	public void close() {
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException exception) {
			throw new UncheckedIOException("Failed to delete generation bundle", exception);
		}
	}

	public record FilePart(Path path, String relativePath, String contentType) {
	}
}
