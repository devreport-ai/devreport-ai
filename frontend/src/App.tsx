/**
 * 앱의 라우트 표(어떤 주소에 어떤 화면을 붙일지)를 정의한다.
 *
 * 라우터 자체(BrowserRouter)는 여기가 아니라 main.tsx 에서 감싼다.
 * 그렇게 나눈 이유는 테스트 때문이다. App 안에 BrowserRouter 가 박혀 있으면
 * 테스트에서 "특정 주소로 진입한 상태"를 만들 수 없다. 분리해 두면 테스트는
 * MemoryRouter 로, 실제 앱은 BrowserRouter 로 각각 감쌀 수 있다.
 */
import { Route, Routes } from 'react-router'
import HomePage from './routes/HomePage'
import NotFoundPage from './routes/NotFoundPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />

      {/* 위 어느 것에도 걸리지 않은 주소를 전부 받아낸다. 반드시 맨 아래에 둔다. */}
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
