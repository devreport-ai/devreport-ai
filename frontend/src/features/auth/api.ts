/**
 * 인증 API. 재발급은 여기 없다 — 401 시 client.ts 가 session.ts 를 부른다.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch, apiFetchNoContent } from '../../lib/api/client'
import { clearAccessToken, isAuthenticated, setAccessToken } from '../../lib/auth/tokenStore'
import type {
  LoginRequest,
  PasswordChangeRequest,
  PasswordResetConfirmRequest,
  PasswordResetRequest,
  PasswordResetRequestedResponse,
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
  // Refresh Token 은 응답의 Set-Cookie 로 온다(#79). 프론트는 Access Token 만 받는다.
  const tokens = await apiFetch<TokenResponse>('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  setAccessToken(tokens.accessToken)
}

/** 로그아웃. 서버가 쿠키의 Refresh Token 을 폐기하고 쿠키를 지운다(#79). */
export function useLogout() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      try {
        await apiFetchNoContent('/api/auth/logout', { method: 'POST' })
      } finally {
        // 서버 폐기가 실패해도 화면은 로그아웃돼야 한다
        clearAccessToken()
        // 이전 사용자 캐시가 다음 사용자에게 보이지 않게 비운다
        client.clear()
      }
    },
  })
}

/** 비밀번호 변경 성공 시 모든 Refresh Token이 폐기되므로 현재 세션도 정리한다. */
export function useChangePassword() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: PasswordChangeRequest) =>
      apiFetchNoContent('/api/auth/password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      clearAccessToken()
      client.clear()
    },
  })
}

export function useRequestPasswordReset() {
  return useMutation({
    mutationFn: (body: PasswordResetRequest) =>
      apiFetch<PasswordResetRequestedResponse>('/api/auth/password-reset/request', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }),
  })
}

export function useResetPassword() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: PasswordResetConfirmRequest) =>
      apiFetchNoContent('/api/auth/password-reset/confirm', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      clearAccessToken()
      client.clear()
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
