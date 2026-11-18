import { useId } from 'react'

/** Local vector artwork: the site's deployment / inspection / verification loop. */
export function MissionScene({ compact = false }: { compact?: boolean }) {
  const id = useId().replace(/:/g, '')
  return <div className={`mission-scene${compact ? ' compact' : ''}`} aria-hidden="true">
    <svg viewBox="0 0 640 480" fill="none">
      <defs>
        <linearGradient id={`${id}-face`} x1="200" y1="80" x2="410" y2="360" gradientUnits="userSpaceOnUse"><stop stopColor="#4b5355"/><stop offset="1" stopColor="#151a1c"/></linearGradient>
        <linearGradient id={`${id}-side`} x1="330" y1="110" x2="430" y2="370" gradientUnits="userSpaceOnUse"><stop stopColor="#343b3b"/><stop offset="1" stopColor="#090d0e"/></linearGradient>
        <linearGradient id={`${id}-beam`} x1="320" y1="90" x2="320" y2="400" gradientUnits="userSpaceOnUse"><stop stopColor="#ddf44b" stopOpacity=".4"/><stop offset="1" stopColor="#ddf44b" stopOpacity="0"/></linearGradient>
      </defs>
      <g stroke="#56605e" strokeOpacity=".25">
        {Array.from({length:9},(_,i)=><path key={`a${i}`} d={`M ${-80+i*90} 310 L ${240+i*90} 480`}/>)}
        {Array.from({length:9},(_,i)=><path key={`b${i}`} d={`M ${720-i*90} 310 L ${400-i*90} 480`}/>)}
      </g>
      <ellipse cx="320" cy="373" rx="228" ry="64" stroke="#59615b" strokeDasharray="3 9"/>
      <ellipse cx="320" cy="373" rx="159" ry="43" stroke="#dcef4a" strokeOpacity=".3"/>
      <path d="M 160 330 L 319 253 L 480 330 L 320 415 Z" fill="#cfe243" fillOpacity=".035" stroke="#cfe243" strokeOpacity=".22"/>
      <g className="mission-object">
        <path d="M 201 146 L 318 82 L 431 145 L 315 211 Z" fill="#59615b" stroke="#87907c"/>
        <path d="M 201 146 L 315 211 L 315 350 L 201 284 Z" fill={`url(#${id}-face)`} stroke="#5b6460"/>
        <path d="M 315 211 L 431 145 L 431 285 L 315 350 Z" fill={`url(#${id}-side)`} stroke="#515a50"/>
        <path d="M 201 182 L 315 247 L 431 182" stroke="#dcef4a" strokeWidth="5"/>
        <path d="M 201 192 L 315 257 L 431 192 L 431 285 L 315 350 L 201 284 Z" fill={`url(#${id}-beam)`}/>
        <path d="M 240 146 L 318 104 L 390 145 L 315 188 Z" fill="#141a18" stroke="#a7b983"/>
        <path d="M 275 144 L 317 121 L 355 143 L 315 168 Z" fill="#dcef4a"/>
        <path d="M 230 233 L 287 266 M 230 245 L 274 271 M 230 257 L 258 273" stroke="#8a9580" strokeWidth="2"/>
        <path d="M 345 264 L 400 232 L 400 256 L 345 288 Z" stroke="#b9cc48"/>
        <path d="M 355 270 L 355 263 M 365 265 L 365 253 M 375 259 L 375 249 M 385 253 L 385 239" stroke="#dcef4a" strokeWidth="3"/>
        <path d="M 201 134 L 201 122 L 220 112 M 411 112 L 431 123 L 431 134 M 315 363 L 315 376" stroke="#dcef4a" strokeWidth="2"/>
      </g>
      <g className="mission-satellite"><path d="M 480 220 L 507 205 L 533 220 L 506 235 Z" fill="#dcef4a"/><path d="M 480 220 L 506 235 L 506 265 L 480 250 Z" fill="#7a872b"/><path d="M 506 235 L 533 220 L 533 250 L 506 265 Z" fill="#a7bb39"/></g>
      <g className="mission-satellite secondary"><path d="M 112 266 L 138 251 L 164 266 L 138 281 Z" fill="#454e48" stroke="#849073"/><path d="M 112 266 L 138 281 L 138 309 L 112 294 Z" fill="#222925" stroke="#56614e"/><path d="M 138 281 L 164 266 L 164 294 L 138 309 Z" fill="#161d18" stroke="#56614e"/></g>
      <g stroke="#849073" strokeOpacity=".7"><path d="M 138 246 L 138 209 L 194 178" strokeDasharray="3 5"/><path d="M 454 164 L 507 135 L 507 192" strokeDasharray="3 5"/><path d="M 315 388 L 315 447 L 420 447"/></g>
      <g fill="#a8b0a3" fontFamily="Consolas, monospace" fontSize="9" letterSpacing="2"><text x="70" y="196">01 / DEPLOY</text><text x="476" y="120">02 / INSPECT</text><text x="430" y="451">03 / VERIFY</text></g>
      <g fill="#dcef4a"><circle cx="194" cy="178" r="3"/><circle cx="454" cy="164" r="3"/><circle cx="315" cy="388" r="3"/></g>
      <path d="M 27 45 L 27 22 L 50 22 M 590 22 L 613 22 L 613 45 M 27 432 L 27 455 L 50 455 M 590 455 L 613 455 L 613 432" stroke="#697262"/>
      <text x="46" y="48" fill="#7e897a" fontFamily="Consolas, monospace" fontSize="9" letterSpacing="2">SHIJIE / QUALITY CORE</text>
      <text x="484" y="48" fill="#dcef4a" fontFamily="Consolas, monospace" fontSize="9" letterSpacing="2">SYSTEM READY</text>
    </svg>
  </div>
}
