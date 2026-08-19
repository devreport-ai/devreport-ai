/** 생성 중인 보고서에 적용할 HTML 템플릿을 고르는 화면. */
import { useEffect, useState } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router'
import { AppNav } from '../components/AppNav'
import { TemplateChoicePanel } from '../features/generation/TemplateChoicePanel'
import {
  clearGenerationRecovery,
  loadGenerationRecovery,
  saveGenerationRecovery,
  useCancelGeneration,
  useGenerationJob,
} from '../features/generation/api'
import { useProject } from '../features/projects/api'
import { ApiError } from '../lib/api/errors'

export default function TemplateChoicePage() {
  const { projectId = '' } = useParams()
  return <TemplateChoiceContent key={projectId} projectId={projectId} />
}

function TemplateChoiceContent({ projectId }: { projectId: string }) {
  const navigate = useNavigate()
  const project = useProject(projectId)
  const [recovery] = useState(() => loadGenerationRecovery(projectId))
  const [templateId, setTemplateId] = useState(recovery?.templateId ?? 'default')
  const job = useGenerationJob(recovery?.jobId ?? null)
  const cancelGeneration = useCancelGeneration()

  useEffect(() => {
    if (job.error instanceof ApiError && job.error.status === 404) {
      clearGenerationRecovery(projectId)
      void navigate(`/projects/${projectId}`, { replace: true })
    }
  }, [job.error, navigate, projectId])

  if (recovery === null) {
    return <Navigate to={`/projects/${projectId}`} replace />
  }

  return (
    <div className="app-page template-choice-page">
      <AppNav screen="HTML 템플릿 선택" />
      <TemplateChoicePanel
        job={job}
        initialTemplateId={templateId}
        recovered={recovery.confirmed}
        projectName={project.data?.name ?? '프로젝트'}
        onTemplateChange={(next) => {
          setTemplateId(next)
          saveGenerationRecovery(projectId, {
            jobId: recovery.jobId,
            templateId: next,
            confirmed: recovery.confirmed,
          })
        }}
        onConfirm={() =>
          saveGenerationRecovery(projectId, {
            jobId: recovery.jobId,
            templateId,
            confirmed: true,
          })
        }
        onRetry={() => {
          clearGenerationRecovery(projectId)
          void navigate(`/projects/${projectId}`, { replace: true })
        }}
        onComplete={() => clearGenerationRecovery(projectId)}
        onCancel={() => {
          if (!window.confirm('보고서 생성을 취소할까요?')) return
          cancelGeneration.mutate(recovery.jobId, {
            onSuccess: () => {
              clearGenerationRecovery(projectId)
              void navigate(`/projects/${projectId}`, { replace: true })
            },
          })
        }}
        canceling={cancelGeneration.isPending}
        cancelError={cancelGeneration.error}
      />
    </div>
  )
}
