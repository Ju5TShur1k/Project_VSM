// Inline SVG, no icon library: a handful of stroke icons in one visual weight.
const PATHS = {
  // front view of a train
  train: (
    <>
      <rect x="5" y="3" width="14" height="13" rx="3" />
      <path d="M5 10h14M8 16l-2 4M16 16l2 4M9 13h.01M15 13h.01" />
    </>
  ),
  database: (
    <>
      <ellipse cx="12" cy="6" rx="7" ry="3" />
      <path d="M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3" />
    </>
  ),
  route: (
    <>
      <circle cx="6" cy="19" r="2" />
      <circle cx="18" cy="5" r="2" />
      <path d="M8 19h8a3 3 0 0 0 0-6H8a3 3 0 0 1 0-6h8" />
    </>
  ),
  plan: (
    <>
      <rect x="5" y="4" width="14" height="17" rx="2" />
      <path d="M9 4V2.5h6V4M9 13l2 2 4-4" />
    </>
  ),
  calendar: (
    <>
      <rect x="3.5" y="5" width="17" height="15" rx="2" />
      <path d="M3.5 10h17M8 3v4M16 3v4" />
    </>
  ),
  bell: <path d="M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15zM10 20.5a2 2 0 0 0 4 0" />,
  alert: <path d="M12 4 21 19H3zM12 10v4M12 16.5h.01" />
}

export function Icon({ name }: { name: keyof typeof PATHS }) {
  return (
    <svg className="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {PATHS[name]}
    </svg>
  )
}

// Badge logo: white high-speed train nose on navy, crimson stripe. Same drawing as public/favicon.svg.
export function Logo() {
  return (
    <svg className="logo" viewBox="0 0 48 48" aria-hidden="true">
      <rect width="48" height="48" rx="10" fill="var(--brand)" />
      <path d="M8 32h23c5 0 9-1.5 9-3.5-2-5-8-8.5-16-8.5H12c-2.2 0-4 1.8-4 4z" fill="#fff" />
      <rect x="11" y="23" width="15" height="2.2" rx="1.1" fill="var(--brand)" />
      <rect x="8" y="28" width="31" height="1.8" fill="var(--accent)" />
      <path d="M10 13h14M6 16.5h10" stroke="#fff" strokeWidth="2" strokeLinecap="round" />
    </svg>
  )
}

// Small "i" with a hover/focus tooltip: short help instead of paragraphs of text.
export function Info({ text }: { text: string }) {
  return <span className="info" tabIndex={0} role="note" aria-label={text} data-tip={text}>i</span>
}
