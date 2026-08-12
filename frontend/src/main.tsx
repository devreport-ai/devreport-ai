/**
 * 앱의 진입점.
 *
 * index.html 의 <div id="root"> 안에 React 앱을 붙인다.
 * 여기서 하는 일은 딱 두 가지뿐이며, 화면 로직은 App.tsx 이하로 내린다.
 *  1) 라우터로 감싸기
 *  2) 전역 스타일 불러오기
 */
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import App from './App'
import './index.css'

const rootElement = document.getElementById('root')

// index.html 이 바뀌어 #root 가 사라지면 조용히 아무것도 안 그려진다.
// 원인을 찾기 어려운 실패라서 명시적으로 터뜨린다.
if (!rootElement) {
  throw new Error('#root 엘리먼트를 찾을 수 없습니다. index.html 을 확인하세요.')
}

createRoot(rootElement).render(
  // StrictMode 는 개발 중에만 동작하며, 부수효과가 있는 코드를 일부러 두 번 실행해
  // 정리(cleanup)를 빠뜨린 곳을 드러낸다. 폴링을 붙일 때 특히 도움이 된다.
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
