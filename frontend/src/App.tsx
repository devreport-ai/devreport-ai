/**
 * 앱의 라우트 표(어떤 주소에 어떤 화면을 붙일지)를 정의한다.
 *
 * 라우터 자체(BrowserRouter)는 여기가 아니라 main.tsx 에서 감싼다.
 * 그렇게 나눈 이유는 테스트 때문이다. App 안에 BrowserRouter 가 박혀 있으면
 * 테스트에서 "특정 주소로 진입한 상태"를 만들 수 없다.
 *
 * 로그인·회원가입을 뺀 모든 화면은 RequireAuth 로 감싼다. 계약이 전역
 * bearerAuth 라 어차피 미로그인 상태로는 아무 API 도 호출할 수 없다 (#61).
 */
import { Route, Routes } from 'react-router'
import { RequireAuth } from './features/auth/RequireAuth'
import { LoginPage } from './routes/LoginPage'
import { SignupPage } from './routes/SignupPage'
import PrintPage from './routes/PrintPage'
import ProjectsPage from './routes/ProjectsPage'
import ProjectPage from './routes/ProjectPage'
import TemplateChoicePage from './routes/TemplateChoicePage'
import ReportPage from './routes/ReportPage'
import NotFoundPage from './routes/NotFoundPage'
import PolicyPage from './routes/PolicyPage'
import SettingsPage from './routes/SettingsPage'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route path="/policies" element={<PolicyPage />} />

      {/* 출력 전용(#39). Bearer 대신 render token 인증이라 RequireAuth 를 안 거친다 */}
      <Route path="/print/:exportId" element={<PrintPage />} />

      <Route
        path="/"
        element={
          <RequireAuth>
            <ProjectsPage />
          </RequireAuth>
        }
      />
      <Route
        path="/projects/:projectId"
        element={
          <RequireAuth>
            <ProjectPage />
          </RequireAuth>
        }
      />
      <Route
        path="/projects/:projectId/templates"
        element={
          <RequireAuth>
            <TemplateChoicePage />
          </RequireAuth>
        }
      />
      <Route
        path="/reports/:reportId"
        element={
          <RequireAuth>
            <ReportPage />
          </RequireAuth>
        }
      />
      <Route
        path="/settings"
        element={
          <RequireAuth>
            <SettingsPage />
          </RequireAuth>
        }
      />

      {/* 위 어느 것에도 걸리지 않은 주소를 전부 받아낸다. 반드시 맨 아래에 둔다. */}
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
