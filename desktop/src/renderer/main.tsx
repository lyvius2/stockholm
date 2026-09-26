import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import { Providers } from './app/Providers'
import './theme/tokens.css'
import './theme/scrollbar.css'
import './theme/base.css'

const container = document.getElementById('root')
if (container === null) throw new Error('#root 요소가 없음')

createRoot(container).render(
  <StrictMode>
    <Providers>
      <App />
    </Providers>
  </StrictMode>,
)
