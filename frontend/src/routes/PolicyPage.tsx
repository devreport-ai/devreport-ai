/** 베타 공개 전 검토할 정책 초안과 데이터 처리 고지. */
import { POLICY_VERSIONS } from '../features/auth/policyVersions'
import { useEffect } from 'react'
import { Link, useLocation } from 'react-router'
import { BrandMark, Icon } from '../components/ui'
import { useAuthState } from '../features/auth/useAuthState'

export default function PolicyPage() {
  const location = useLocation()
  // 로그인 상태면 로그인 화면이 아니라 홈으로 돌아가야 한다 (#130)
  const backTo = useAuthState() ? '/' : '/login'

  // 브라우저는 SPA 라우팅에서 주소의 #앵커로 스크롤해 주지 않는다 (#130)
  useEffect(() => {
    const id = location.hash.slice(1)
    if (!id) return
    // 첫 페인트 뒤에 옮겨야 한다. 폰트·이미지로 높이가 밀리면 위치가 어긋난다.
    const timer = setTimeout(() => {
      document.getElementById(id)?.scrollIntoView?.({ block: 'start' }) // jsdom 에는 없다
    }, 60)
    return () => clearTimeout(timer)
  }, [location.hash])

  return (
    <main className="plain-page">
      <header className="plain-page__header">
        <Link to={backTo} aria-label="DevReport AI">
          <BrandMark compact />
        </Link>
        <Link to={backTo} className="plain-page__back-link">
          {backTo === '/' ? '프로젝트로 돌아가기' : '로그인으로 돌아가기'}
        </Link>
      </header>

      <div className="policy-content">
        <header className="policy-content__heading">
          <p className="eyebrow">DEVREPORT AI / POLICIES</p>
          <h1>개인정보처리방침·이용약관</h1>
          <p className="policy-draft-warning">
            <Icon name="alert" size={16} />
            <span>
              이 페이지는 베타 공개 전 검토를 위한 초안입니다. 정식 공개 전 법률·개인정보 담당자
              검토와 운영자 연락처·삭제 요청 SLA 확정이 필요합니다.
            </span>
          </p>
          <p>
            개인정보처리방침 {POLICY_VERSIONS.privacyPolicy} · 이용약관{' '}
            {POLICY_VERSIONS.termsOfService}
          </p>
        </header>

        <section id="ai-data" className="policy-section">
          <h2>AI 자료 처리 고지</h2>
          <p>
            사용자가 선택한 ZIP·MD·TXT·PNG·JPG 자료와 생성 지시사항은 보고서 생성 과정에서 Backend를
            거쳐 AI Service에 전달될 수 있습니다. Frontend가 AI 제공자를 직접 호출하지 않으며,
            생성이 끝나면 Backend의 임시 bundle은 삭제됩니다.
          </p>
          <p>
            업로드·생성 전에 이 고지를 확인해야 하며, 민감하거나 제3자의 개인정보가 포함된 자료는
            권한과 처리 근거를 확인한 뒤 업로드해야 합니다.
          </p>
        </section>

        <section id="privacy" className="policy-section">
          <h2>개인정보처리방침 (초안)</h2>
          <p>
            서비스는 계정 이메일·이름, 프로젝트·파일·보고서·생성 작업·PDF 내보내기 상태를 기능
            제공과 장애 대응을 위해 처리합니다. 사용 이벤트에는 원본 파일·프롬프트·보고서
            문서·개인정보를 기록하지 않습니다.
          </p>
          <ul>
            <li>프로젝트와 업로드 파일은 삭제하면 휴지통에서 30일간 복구할 수 있습니다.</li>
            <li>
              휴지통 프로젝트는 삭제 후 30일이 경과하면 정리 작업에서 메타데이터와 파일을 완전
              삭제합니다.
            </li>
            <li>완료된 PDF는 기본 24시간 후 만료·삭제됩니다.</li>
            <li>비식별 사용 이벤트는 최대 90일 보관하며 회원·프로젝트 삭제 시 함께 삭제됩니다.</li>
          </ul>
          <p>
            사용자는 프로젝트 화면에서 파일을 개별 삭제하거나 프로젝트를 휴지통으로 이동하고, 30일
            안에 복구할 수 있습니다. 계정 삭제 API와 공식 요청 채널은 베타 공개 전에 확정해야
            합니다.
          </p>
        </section>

        <section id="terms" className="policy-section">
          <h2>이용약관 (초안)</h2>
          <ul>
            <li>사용자는 업로드 자료를 처리할 권리와 필요한 동의를 확보해야 합니다.</li>
            <li>AI가 만든 결과는 사용자가 검토·수정한 뒤 사용해야 합니다.</li>
            <li>
              서비스를 타인의 권리를 침해하거나 보안 검사를 우회하는 용도로 사용할 수 없습니다.
            </li>
            <li>장애·보안 사고 문의 담당자와 처리 기준은 베타 공개 전에 공지합니다.</li>
          </ul>
        </section>
      </div>
    </main>
  )
}
