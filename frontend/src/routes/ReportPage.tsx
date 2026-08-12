/**
 * 보고서 편집기 자리표시.
 *
 * 실제 편집기는 이슈 #38 에서 만든다. 여기서는 생성 완료 후 이동이 실제로
 * 동작하는지 확인할 수 있을 만큼만 둔다. reportId 를 보여주는 이유도 그것이다.
 */
import { Link, useParams } from 'react-router'

export default function ReportPage() {
  const { reportId } = useParams()

  return (
    <main className="mx-auto max-w-3xl p-6">
      <h1 className="text-2xl font-bold">보고서</h1>
      <p className="mt-2 text-gray-700">
        보고서 ID: <code>{reportId}</code>
      </p>
      <p className="mt-2 text-gray-500">편집기는 이슈 #38 에서 구현합니다.</p>
      <Link to="/" className="mt-4 inline-block underline">
        프로젝트 목록으로
      </Link>
    </main>
  )
}
