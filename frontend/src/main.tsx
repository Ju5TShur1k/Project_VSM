import React from 'react'
import ReactDOM from 'react-dom/client'
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import { Unauthorized } from './api'
import './index.css'

// Any 401 (e.g. expired session) re-checks /auth/me, which flips the app back
// to the login screen. /auth/me itself is excluded to avoid a refetch loop.
const onError = (err: unknown, key: readonly unknown[] | undefined) => {
  if (err instanceof Unauthorized && key?.[0] !== 'me') queryClient.invalidateQueries({ queryKey: ['me'] })
}

const queryClient: QueryClient = new QueryClient({
  queryCache: new QueryCache({ onError: (err, q) => onError(err, q.queryKey) }),
  mutationCache: new MutationCache({ onError: (err) => onError(err, undefined) })
})

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>
)
