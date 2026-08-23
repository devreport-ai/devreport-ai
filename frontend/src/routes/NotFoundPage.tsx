/**
 * 정의되지 않은 경로로 들어왔을 때 보여줄 화면.
 *
 * 초기 구성 단계에서 이걸 미리 넣어두는 이유는, 이게 없으면 오타 난 주소로
 * 접속했을 때 빈 흰 화면만 뜨기 때문이다. 라우팅이 잘못된 것인지
 * 렌더링이 실패한 것인지 구분이 안 돼 디버깅이 어려워진다.
 */
import { Link } from 'react-router'
import { BrandMark } from '../components/ui'

export default function NotFoundPage() {
  return (
    <main className="plain-page">
      <header className="plain-page__header">
        <Link to="/" aria-label="DevReport AI">
          <BrandMark compact />
        </Link>
      </header>
      <div className="not-found-content">
        <span className="not-found-content__badge">오류 404</span>
        <h1>페이지를 찾을 수 없습니다</h1>
        <p>요청하신 주소가 존재하지 않습니다.</p>
        <Link to="/" className="primary-button">
          ← 홈으로 돌아가기
        </Link>
      </div>
    </main>
  )
}
