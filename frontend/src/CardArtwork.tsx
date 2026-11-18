const artwork = [
  '/images/industrial-outpost.webp',
  '/images/glass-core.webp',
  '/images/testing-corridor.webp',
]

/** Decorative art stays outside the card's readable content and click targets. */
export function CardArtwork({ index,theme }: { index: number;theme?:'sites'|'workbench' }) {
  return <div className="card-artwork" aria-hidden="true">
    <img src={theme?`/images/visual-${theme}.webp`:artwork[index % artwork.length]} alt="" loading="lazy" decoding="async"/>
  </div>
}
