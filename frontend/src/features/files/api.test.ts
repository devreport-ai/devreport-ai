import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchAllProjectFiles } from './api'

function page(pageNumber: number, fileId: string) {
  return {
    items: [
      {
        id: fileId,
        projectId: 'project-1',
        originalName: `${fileId}.png`,
        contentType: 'image/png',
        sizeBytes: 1,
        createdAt: '2026-08-14T00:00:00Z',
      },
    ],
    page: pageNumber,
    size: 100,
    totalElements: 2,
    totalPages: 2,
  }
}

afterEach(() => vi.unstubAllGlobals())

describe('fetchAllProjectFiles', () => {
  it('첫 페이지 이후의 파일도 모두 합친다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((rawUrl: string) =>
        Promise.resolve(
          new Response(
            JSON.stringify(rawUrl.includes('page=1') ? page(1, 'file-2') : page(0, 'file-1')),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      ),
    )

    const result = await fetchAllProjectFiles('project-1')

    expect(result.items.map((file) => file.id)).toEqual(['file-1', 'file-2'])
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})
