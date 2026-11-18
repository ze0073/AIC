export type CharacterState='idle'|'reply'|'working'|'complete'|'error'
export function GuideCharacter({state='idle',small=false}:{state?:CharacterState;small?:boolean}){
  return <span className={'guide-character '+state+(small?' small':'')} aria-hidden="true">
    <svg viewBox="0 0 120 132" fill="none">
      <ellipse className="guide-shadow" cx="60" cy="121" rx="34" ry="6" fill="#374137" opacity=".13"/>
      <g className="guide-body">
        <path d="M60 12V25" stroke="#657719" strokeWidth="4" strokeLinecap="round"/><circle className="guide-antenna" cx="60" cy="10" r="6" fill="#dcef4a" stroke="#344231" strokeWidth="2"/>
        <rect x="18" y="25" width="84" height="62" rx="24" fill="#edf1e5" stroke="#3b4937" strokeWidth="3"/>
        <rect x="27" y="37" width="66" height="33" rx="13" fill="#303d30"/>
        <g className="guide-eyes" fill="#dcef4a"><rect x="40" y="47" width="8" height="13" rx="4"/><rect x="72" y="47" width="8" height="13" rx="4"/></g>
        <path className="guide-smile" d="M54 63Q60 69 66 63" stroke="#dcef4a" strokeWidth="2" strokeLinecap="round"/>
        <path d="M42 87H78L83 108Q60 121 37 108Z" fill="#657719" stroke="#344231" strokeWidth="2"/>
        <path d="M53 94H67V104H53Z" fill="#dcef4a"/><path className="guide-hand" d="M25 93L33 101M94 92L86 101" stroke="#657719" strokeWidth="7" strokeLinecap="round"/>
      </g>
    </svg>
  </span>
}
