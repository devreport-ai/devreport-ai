/** 보고서 편집 화면 (#38). 조회가 끝나면 편집기에 넘긴다. */
import { Link, useLocation, useParams } from 'react-router'
import { useReport } from '../features/report/api'
import { ReportEditor } from '../features/report/ReportEditor'
import { toDisplayMessage } from '../lib/api/errors'

export default function ReportPage() {
  const { reportId } = useParams()
  const location = useLocation()
  const report = useReport(reportId ?? '')

  if (report.isPending) {
    return <p className="p-6 text-gray-500">보고서를 불러오는 중…</p>
  }

  if (report.error) {
    return (
      <main className="mx-auto max-w-3xl p-6">
        <p role="alert" className="text-red-600">
          {toDisplayMessage(report.error)}
        </p>
        <Link to="/" className="mt-4 inline-block underline">
          프로젝트 목록으로
        </Link>
      </main>
    )
  }

  // 생성 흐름(#36)이 넘긴 템플릿 선택값. 새로고침하면 사라지지만 그때는 저장된 값을 쓴다.
  const initialTemplateId = (location.state as { templateId?: string } | null)?.templateId

  // key 로 보고서마다 편집기를 새로 마운트한다. 같은 인스턴스가 다른 보고서를 받으면
  // 편집 상태와 저장 기준(expectedVersion)이 이전 것으로 남아 409 를 만든다 (리뷰 지적)
  return (
    <ReportEditor key={report.data.id} report={report.data} initialTemplateId={initialTemplateId} />
  )
}
