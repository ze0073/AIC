export type GuideSpeechResult={status:'started'|'cancelled'}|{status:'unavailable';message:string}

const voiceLoadTimeout=2500
let speechRequest=0
let cancelVoiceWait:(()=>void)|undefined
const chineseVoice=(synth:SpeechSynthesis)=>synth.getVoices().find(voice=>voice.lang.toLowerCase().startsWith('zh'))

/** Voices can be populated after the first getVoices() call, including in stages. */
function waitForChineseVoice(synth:SpeechSynthesis):Promise<SpeechSynthesisVoice|null>{
  return new Promise(resolve=>{
    let settled=false
    const finish=(voice:SpeechSynthesisVoice|null)=>{
      if(settled)return
      settled=true;clearTimeout(timeout);clearInterval(poll)
      synth.removeEventListener?.('voiceschanged',check)
      if(cancelVoiceWait===cancel)cancelVoiceWait=undefined
      resolve(voice)
    }
    const check=()=>{const voice=chineseVoice(synth);if(voice)finish(voice)}
    const cancel=()=>finish(null)
    const timeout=setTimeout(()=>finish(chineseVoice(synth)||null),voiceLoadTimeout)
    const poll=setInterval(check,100)
    cancelVoiceWait=cancel
    synth.addEventListener?.('voiceschanged',check)
    check()
  })
}

/** Only a user's play gesture starts audio. Page refresh never resumes it. */
export async function speakGuide(text:string):Promise<GuideSpeechResult>{
  stopGuideSpeech()
  const request=speechRequest
  if(!('speechSynthesis' in window)||!('SpeechSynthesisUtterance' in window))
    return {status:'unavailable',message:'此浏览器暂不支持语音播报，请继续阅读文字。'}
  const synth=window.speechSynthesis
  const voice=chineseVoice(synth)||await waitForChineseVoice(synth)
  if(request!==speechRequest)return {status:'cancelled'}
  if(!voice)return {status:'unavailable',message:'当前设备没有可用中文声音，请继续阅读文字。Windows 可在“设置 → 时间和语言 → 语音”添加中文声音，重启浏览器后再试。'}
  const utterance=new SpeechSynthesisUtterance(text)
  utterance.voice=voice;utterance.lang=voice.lang;utterance.rate=1
  try{synth.speak(utterance);return {status:'started'}}
  catch{return {status:'unavailable',message:'语音播报未能启动，请重试或继续阅读文字。'}}
}

export function stopGuideSpeech(){
  speechRequest++;cancelVoiceWait?.()
  if('speechSynthesis' in window)window.speechSynthesis.cancel()
}
