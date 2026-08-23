package ai.devreport.backend.integration.ai;

import ai.devreport.backend.upload.domain.UploadedFile;
import ai.devreport.backend.upload.infrastructure.SafeZipExtractor;
import ai.devreport.backend.upload.infrastructure.UploadedFileRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class GenerationBundleFactoryTest {

	@TempDir
	Path uploadRoot;

	@Test
	void buildsManifestFromAllowedFilesAndDeletesTemporaryBundle() throws Exception {
		UUID projectId = UUID.randomUUID();
		UploadedFile zip = file(projectId, "project.zip", "application/zip", 1);
		UploadedFile document = file(projectId, "notes.md", "text/markdown", 4);
		UploadedFile image = file(projectId, "screen.png", "image/png", 3);
		UploadedFile pdf = file(projectId, "assignment.pdf", "application/pdf", 4);
		Path projectRoot = Files.createDirectories(uploadRoot.resolve(projectId.toString()));

		Path zipPath = projectRoot.resolve(zip.getStoredName());
		writeZip(zipPath);
		new SafeZipExtractor().extract(zipPath, projectRoot.resolve(zip.getStoredName() + ".extracted"));
		Files.writeString(projectRoot.resolve(document.getStoredName()), "문서", StandardCharsets.UTF_8);
		Files.write(projectRoot.resolve(image.getStoredName()), new byte[]{1, 2, 3});
		Files.writeString(projectRoot.resolve(pdf.getStoredName()), "%PDF-1.4\n%%EOF");

		UploadedFileRepository repository = mock(UploadedFileRepository.class);
		for (UploadedFile file : List.of(zip, document, image, pdf)) {
			when(repository.findById(file.getId())).thenReturn(Optional.of(file));
		}
		var objectMapper = JsonMapper.builder().build();
		var factory = new GenerationBundleFactory(repository, objectMapper, uploadRoot.toString());
		GenerationBundle bundle = factory.create(new GenerationRequest(
			List.of(zip.getId(), document.getId(), image.getId(), pdf.getId()), Map.of(), "요약", null, null));
		Path bundleRoot = bundle.root();

		assertThat(bundle.files()).extracting(GenerationBundle.FilePart::relativePath).containsExactly(
			"source/" + zip.getId() + "/README.md",
			"source/" + zip.getId() + "/src/App.java",
			"documents/" + document.getId() + "/notes.md",
			"images/" + image.getId() + "/screen.png",
			"documents/" + pdf.getId() + "/assignment.pdf"
		).noneMatch(path -> path.endsWith("inside.png"));
		String manifest = Files.readString(bundle.manifest());
		assertThat(manifest).contains(zip.getId().toString(), document.getId().toString(), image.getId().toString(),
			pdf.getId().toString()).contains("\"mimeType\"", "\"size\"");
		var manifestFiles = objectMapper.readTree(manifest).get("files");
		assertThat(manifestFiles.size()).isEqualTo(bundle.files().size());
		for (int index = 0; index < bundle.files().size(); index++) {
			GenerationBundle.FilePart part = bundle.files().get(index);
			assertThat(manifestFiles.get(index).get("path").stringValue()).isEqualTo(part.relativePath());
			assertThat(manifestFiles.get(index).get("size").asLong()).isEqualTo(Files.size(part.path()));
		}
		assertThat(bundle.files()).allSatisfy(part -> assertThat(part.path()).isRegularFile());

		bundle.close();
		assertThat(bundleRoot).doesNotExist();
	}

	private UploadedFile file(UUID projectId, String name, String contentType, long size) {
		return new UploadedFile(projectId, name, contentType, size);
	}

	private static void writeZip(Path path) throws IOException {
		try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
			entry(zip, "src/App.java", "class App {}");
			entry(zip, "README.md", "# Project");
			entry(zip, "inside.png", "not-an-image");
			entry(zip, "build/generated.java", "class Generated {}");
		}
	}

	private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}
