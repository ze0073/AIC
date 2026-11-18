import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {speakGuide,stopGuideSpeech} from './guideSpeech'

let voices:SpeechSynthesisVoice[],synth:SpeechSynthesis
const chinese={lang:'zh-CN',name:'中文'} as SpeechSynthesisVoice
const english={lang:'en-US',name:'English'} as SpeechSynthesisVoice
beforeEach(()=>{
  vi.useFakeTimers();voices=[]
  synth=Object.assign(new EventTarget(),{getVoices:vi.fn(()=>voices),speak:vi.fn(),cancel:vi.fn()}) as unknown as SpeechSynthesis
  vi.stubGlobal('speechSynthesis',synth)
  vi.stubGlobal('SpeechSynthesisUtterance',class{constructor(public text:string){}})
})
afterEach(()=>{stopGuideSpeech();vi.useRealTimers();vi.unstubAllGlobals()})

describe('guide speech voice initialization',()=>{
  it('plays the first click when Chinese voices arrive asynchronously',async()=>{
    const remove=vi.spyOn(synth,'removeEventListener'),result=speakGuide('第一次点击')
    expect(synth.speak).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(200)
    voices=[chinese];synth.dispatchEvent(new Event('voiceschanged'))
    expect(await result).toEqual({status:'started'})
    expect(synth.speak).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({text:'第一次点击',voice:chinese}))
    expect(remove).toHaveBeenCalledWith('voiceschanged',expect.any(Function))
    expect(vi.getTimerCount()).toBe(0)
  })
  it('waits for Chinese voices even if other languages load first',async()=>{
    voices=[english];const result=speakGuide('你好')
    synth.dispatchEvent(new Event('voiceschanged'));expect(synth.speak).not.toHaveBeenCalled()
    voices=[english,chinese];synth.dispatchEvent(new Event('voiceschanged'))
    expect(await result).toEqual({status:'started'})
    expect(synth.speak).toHaveBeenCalledWith(expect.objectContaining({voice:chinese}))
  })
  it('recovers when voices load without a voiceschanged event',async()=>{
    const result=speakGuide('你好');voices=[chinese]
    await vi.advanceTimersByTimeAsync(100)
    expect(await result).toEqual({status:'started'});expect(synth.speak).toHaveBeenCalledOnce()
  })
  it('shows the missing voice message only after the loading deadline',async()=>{
    voices=[english];const settled=vi.fn(),result=speakGuide('你好');result.then(settled)
    await vi.advanceTimersByTimeAsync(2400);expect(settled).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(100)
    expect(await result).toEqual({status:'unavailable',message:expect.stringContaining('没有可用中文声音')})
    expect(synth.speak).not.toHaveBeenCalled();expect(vi.getTimerCount()).toBe(0)
  })
  it('cancels pending initialization so a later voice event cannot start playback',async()=>{
    const result=speakGuide('关闭后不能播报');stopGuideSpeech()
    voices=[chinese];synth.dispatchEvent(new Event('voiceschanged'))
    expect(await result).toEqual({status:'cancelled'})
    await vi.advanceTimersByTimeAsync(3000)
    expect(synth.speak).not.toHaveBeenCalled();expect(vi.getTimerCount()).toBe(0)
  })
  it('supersedes an older pending reply without playing it twice',async()=>{
    const old=speakGuide('旧回复'),latest=speakGuide('新回复')
    voices=[chinese];synth.dispatchEvent(new Event('voiceschanged'))
    expect(await old).toEqual({status:'cancelled'});expect(await latest).toEqual({status:'started'})
    expect(synth.speak).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({text:'新回复'}))
  })
  it('plays immediately when a Chinese voice is already available',async()=>{
    voices=[chinese];const result=speakGuide('你好')
    expect(synth.speak).toHaveBeenCalledOnce();expect(await result).toEqual({status:'started'})
    expect(vi.getTimerCount()).toBe(0)
  })
})
