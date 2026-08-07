package ai.devreport.backend.project;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectService {

	private final ProjectRepository projects;

	ProjectService(ProjectRepository projects) {
		this.projects = projects;
	}

	Project create(UUID ownerId, String name) {
		return projects.save(new Project(ownerId, name));
	}

	@Transactional(readOnly = true)
	Page<Project> list(UUID ownerId, int page, int size) {
		Sort sort = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
		return projects.findAllByOwnerIdAndDeletedAtIsNull(ownerId, PageRequest.of(page, size, sort));
	}

	@Transactional(readOnly = true)
	Page<Project> trash(UUID ownerId, int page, int size) {
		Sort sort = Sort.by(Sort.Order.desc("deletedAt"), Sort.Order.desc("id"));
		return projects.findAllByOwnerIdAndDeletedAtIsNotNull(ownerId, PageRequest.of(page, size, sort));
	}

	@Transactional(readOnly = true)
	Project get(UUID ownerId, UUID projectId) {
		return ownedProject(ownerId, projectId);
	}

	@Transactional(readOnly = true)
	public void requireOwned(UUID ownerId, UUID projectId) {
		ownedProject(ownerId, projectId);
	}

	public void lock(UUID ownerId, UUID projectId) {
		projects.findOwnedForUpdate(projectId, ownerId).orElseThrow(ProjectNotFoundException::new);
	}

	public List<Project> findExpired(Instant deletedBefore, Pageable pageable) {
		return projects.findAllByDeletedAtBefore(deletedBefore, pageable);
	}

	public void purge(Project project) {
		projects.delete(project);
	}

	Project update(UUID ownerId, UUID projectId, String name) {
		Project project = ownedProject(ownerId, projectId);
		project.rename(name);
		return project;
	}

	void delete(UUID ownerId, UUID projectId) {
		ownedProject(ownerId, projectId).delete();
	}

	Project restore(UUID ownerId, UUID projectId) {
		Project project = projects.findByIdAndOwnerIdAndDeletedAtIsNotNull(projectId, ownerId)
			.orElseThrow(ProjectNotFoundException::new);
		project.restore();
		return project;
	}

	private Project ownedProject(UUID ownerId, UUID projectId) {
		return projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
			.orElseThrow(ProjectNotFoundException::new);
	}
}
