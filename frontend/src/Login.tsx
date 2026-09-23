import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from './api'

export default function Login() {
  const qc = useQueryClient()
  const login = useMutation({
    mutationFn: (v: { username: string; password: string }) => api.login(v.username, v.password),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['me'] })
  })

  return (
    <main className="narrow">
      <h1>
        ОКНО ВСМ <span>/ Вход</span>
      </h1>
      <form
        className="card form"
        onSubmit={(e) => {
          e.preventDefault()
          const f = new FormData(e.currentTarget)
          login.mutate({ username: String(f.get('username')), password: String(f.get('password')) })
        }}
      >
        <label>
          Логин
          <input name="username" autoComplete="username" required autoFocus />
        </label>
        <label>
          Пароль
          <input name="password" type="password" autoComplete="current-password" required />
        </label>
        <button disabled={login.isPending}>{login.isPending ? 'Вход…' : 'Войти'}</button>
        {login.isError && (
          <p className="error" role="alert">
            {(login.error as Error).message}
          </p>
        )}
      </form>
    </main>
  )
}
