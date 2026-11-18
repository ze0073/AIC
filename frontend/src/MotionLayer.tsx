import { useEffect } from 'react'
import { installMotionEffects } from './motionEffects'

export function MotionLayer() {
  useEffect(() => {
    const root = document.getElementById('root')
    if (root) return installMotionEffects(root)
  }, [])
  return null
}
