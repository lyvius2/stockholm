import { render, screen } from '@testing-library/react'
import { App } from '@renderer/app/App'
import { Providers } from '@renderer/app/Providers'

describe('App', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'stockholm', {
      configurable: true,
      value: {
        daemon: {
          status: () => Promise.resolve({ reachable: false, baseUrl: 'http://127.0.0.1:2609' }),
        },
        theme: { current: () => Promise.resolve('light') },
      },
    })
  })

  it('상단 바에 브랜드를 보임', () => {
    render(
      <Providers>
        <App />
      </Providers>,
    )
    expect(screen.getByText('Stockholm')).toBeDefined()
  })
})
