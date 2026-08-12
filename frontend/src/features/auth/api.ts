/**
 * 인증 API. 재발급은 여기 없다 — 401 시 client.ts 가 session.ts 를 부른다.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch, apiFetchNoContent } from '../../lib/api/client'
import { clearTokens, getRefreshToken, isAuthenticated, setTokens } from '../../lib/auth/tokenStore'
import type {
  LoginRequest,
  SignupRequest,
  TokenResponse,
  UserResponse,
} from '../../lib/contracts/types'

export const authKeys = {
  me: ['auth', 'me'] as const,
}

/** 회원가입 후 자동 로그인. signup 은 토큰을 안 주므로 login 을 이어 호출한다. */
export function useSignup() {
  return useMutation({
    mutationFn: async (body: SignupRequest) => {
      await apiFetch<UserResponse>('/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      return login({ email: body.email, password: body.password })
    },
  })
}

export function useLogin() {
  return useMutation({ mutationFn: login })
}

async function login(body: LoginRequest): Promise<void> {
  const tokens = await apiFetch<TokenResponse>('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  setTokens(tokens)
}

/** 로그아웃. 서버 토큰 폐기 → 로컬 정리 순서 (로컬부터 지우면 폐기 요청을 못 보낸다). */
export function useLogout() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const refreshToken = getRefreshToken()
      try {
        if (refreshToken !== null) {
          await apiFetchNoContent('/api/auth/logout', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken }),
          })
        }
      } finally {
        clearTokens()
        // 이전 사용자 캐시가 다음 사용자에게 보이지 않게 비운다
        client.clear()
      }
    },
  })
}

/** 로그인한 사용자 정보. */
export function useMe() {
  return useQuery({
    queryKey: authKeys.me,
    queryFn: () => apiFetch<UserResponse>('/api/auth/me'),
    enabled: isAuthenticated(),
    staleTime: Infinity, // 세션 중에 바뀌지 않는 값이다
  })
}
