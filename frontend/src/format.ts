// Model time is Moscow time; <input type="datetime-local"> works in wall-clock strings.
const opts = { timeZone: 'Europe/Moscow' } as const
export const time = (iso: string) => new Date(iso).toLocaleString('ru-RU', { ...opts, day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
export const clock = (iso: string) => new Date(iso).toLocaleTimeString('ru-RU', { ...opts, hour: '2-digit', minute: '2-digit' })
export const toInput = (iso: string) => new Date(iso).toLocaleString('sv-SE', opts).replace(' ', 'T').slice(0, 16)
export const toIso = (local: string) => `${local}:00+03:00`
export const shift = (local: string, minutes: number) => toInput(new Date(Date.parse(toIso(local)) + minutes * 60_000).toISOString())
