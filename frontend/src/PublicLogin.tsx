import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { App, Alert, Button, Card, ConfigProvider, Form, Input, Layout, Segmented, theme } from 'antd'
import { ArrowRightOutlined, CloudUploadOutlined, SafetyCertificateOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { request, resetCsrf } from './api'
import type { AuthUser } from './types'
import './hosting.css'
import './login-navigation.css'
import { MissionScene } from './MissionScene'
import { BrandLogo } from './BrandLogo'

const capabilities = [
  { label: '部署网站', english: 'DEPLOY', icon: <CloudUploadOutlined/>, description: '上传网页或连接代码仓库，从构建到上线，在同一个工作区完成。' },
  { label: '智能检测', english: 'INSPECT', icon: <ThunderboltOutlined/>, description: '选择网站、描述目标。确认检查方案后，查看页面、安全与性能的真实证据。' },
  { label: '修复验证', english: 'VERIFY', icon: <SafetyCertificateOutlined/>, description: '理解问题、审阅源码修改，再通过复测验证，让每一次改进都有据可循。' },
]

function focusLoginSection(hash: string) {
  if (!['#overview', '#capabilities', '#login-panel'].includes(hash)) return
  const section = document.getElementById(hash.slice(1))
  if (!section) return
  const focusTarget = hash === '#login-panel'
    ? section.querySelector<HTMLInputElement>('input[autocomplete="username"]')
    : section
  focusTarget?.focus({ preventScroll: true })
  section.scrollIntoView({
    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
    block: hash === '#overview' ? 'start' : 'center',
  })
}

export function PublicLogin({onSuccess}:{onSuccess:()=>void}){
  const location = useLocation(), navigate = useNavigate()
  const activeSection = location.hash || '#overview'
  useEffect(() => { focusLoginSection(location.hash) }, [location.hash, location.key])
  const {message}=App.useApp(),[mode,setMode]=useState('login'),[form]=Form.useForm()
  const [capability,setCapability]=useState(0)
  const captcha=useQuery({queryKey:['registration-captcha'],queryFn:()=>request<{id:string;image:string}>('/auth/captcha'),enabled:mode==='register',retry:false})
  const submit=useMutation({mutationFn:(values:Record<string,string>)=>request<AuthUser>(`/auth/${mode}`,{method:'POST',body:JSON.stringify(mode==='register'?{...values,captchaId:captcha.data?.id}:values)}),onSuccess:()=>{resetCsrf();message.success(mode==='register'?'账号已创建，开始部署你的网站':'登录成功');onSuccess()},onError:error=>{message.error((error as Error).message);if(mode==='register'){captcha.refetch();form.setFieldValue('captcha','')}}})
  return <ConfigProvider theme={{algorithm:theme.darkAlgorithm,token:{colorPrimary:'#dcef4a',colorBgContainer:'#1a1e1d',colorText:'#f1f3e9',colorTextSecondary:'#a2aa9c',colorBorder:'#454c40',borderRadius:3},components:{Button:{primaryColor:'#181d12',colorPrimary:'#dcef4a',colorPrimaryHover:'#e7fa78',colorPrimaryActive:'#c2d438'}}}}><Layout className="login-shell industrial-login">
    <img className="login-background" src="/images/industrial-outpost.webp" alt="" fetchPriority="high" decoding="async"/>
    <header className="login-topbar"><Link to="#overview" replace={location.hash==='#overview'} className="brand brand-integrated" aria-label="视界，平台概览"><BrandLogo/></Link><nav aria-label="页面导航"><Link to="#overview" replace={location.hash==='#overview'} aria-current={activeSection==='#overview'?'location':undefined}>平台概览 <span>01</span></Link><Link to="#capabilities" replace={location.hash==='#capabilities'} aria-current={activeSection==='#capabilities'?'location':undefined}>核心能力 <span>02</span></Link><Link to="#login-panel" replace={location.hash==='#login-panel'} aria-current={activeSection==='#login-panel'?'location':undefined}>进入工作台 <ArrowRightOutlined/></Link></nav></header>
    <main className="login-main"><div className="account-intro" id="overview" tabIndex={-1} aria-labelledby="overview-title"><div className="login-eyebrow"><span className="live-dot"/> 网站部署与质量工作台 <span className="eyebrow-divider"/> BUILT FOR BETTER WEB</div><h1 id="overview-title">看见问题。<br/><em>交付更好的网站。</em></h1><p>从第一行代码，到每一次上线。<br/>让部署、检测与修复，成为一段连贯的旅程。</p><Button className="login-explore" type="text" onClick={()=>navigate('#login-panel',{replace:location.hash==='#login-panel'})}>开启你的工作空间 <ArrowRightOutlined/></Button><MissionScene/><div className="scene-caption"><span>DEPLOY. INSPECT. IMPROVE.</span><span>网站的下一步，从这里开始 ↗</span></div></div>
    <Card className="login-card" id="login-panel">
    <div className="login-panel-code"><span>ACCESS / 工作空间</span><span>↗</span></div>
    <div className="login-brand"><div><h2>{mode==='register'?'创建你的工作空间':'欢迎回到视界'}</h2><span>{mode==='register'?'从一个账号开始，让网站持续变好。':'登录后，继续你的网站质量旅程。'}</span></div></div>
    <Segmented block value={mode} onChange={value=>{setMode(value);form.resetFields()}} options={[{value:'login',label:'登录'},{value:'register',label:'创建账号'}]}/>
    <Form form={form} layout="vertical" onFinish={values=>submit.mutate(values)} style={{marginTop:24}}>
      {mode==='register'&&<Form.Item name="displayName" label="怎么称呼你" rules={[{required:true,message:'请输入显示名称'},{max:120}]}><Input size="large" autoComplete="nickname" placeholder="例如：小林"/></Form.Item>}
      <Form.Item name="username" label="用户名" rules={[{required:true,message:'请输入用户名'},...(mode==='register'?[{pattern:/^[a-z0-9._-]{3,80}$/,message:'使用 3–80 位小写字母、数字、点、下划线或连字符'}]:[])]}><Input size="large" autoComplete="username" placeholder="你的登录用户名"/></Form.Item>
      <Form.Item name="password" label="密码" rules={[{required:true,message:'请输入密码'},...(mode==='register'?[{min:10,max:200,message:'密码至少 10 位'}]:[])]}><Input.Password size="large" autoComplete={mode==='register'?'new-password':'current-password'}/></Form.Item>
      {mode==='register'&&<><Form.Item name="captcha" label="图片验证码" rules={[{required:true,message:'请输入图片中的字符'}]}><Input size="large" maxLength={5} autoComplete="off" addonAfter={<button className="captcha-refresh" type="button" aria-label="刷新验证码" onClick={()=>captcha.refetch()}>{captcha.data?<img src={captcha.data.image} alt="注册验证码，点击刷新"/>:'点击获取验证码'}</button>}/></Form.Item><p className="account-note">每个账号可创建 3 个项目，同时运行 2 个环境。网站源码和私有预览仅对项目成员开放。</p></>}
      {captcha.isError&&mode==='register'&&<Alert type="warning" title="验证码加载失败" action={<Button onClick={()=>captcha.refetch()}>重试</Button>}/>}
      <Button block size="large" type="primary" htmlType="submit" loading={submit.isPending} disabled={mode==='register'&&!captcha.data}>{mode==='register'?'创建账号并开始':'登录工作台'}</Button>
    </Form>
    <div className="login-card-foot"><SafetyCertificateOutlined/><span>源码与私有预览仅对项目成员开放</span></div>
  </Card></main>
  <section className="login-capabilities" id="capabilities" tabIndex={-1} aria-label="核心能力"><div className="capability-heading"><span>THE WORKFLOW</span><b>每一步，都有答案。</b></div><div className="capability-tabs">{capabilities.map((item,index)=><button type="button" key={item.english} className={capability===index?'active':''} aria-pressed={capability===index} onClick={()=>setCapability(index)}><span className="capability-number">0{index+1}</span><span>{item.icon}<b>{item.label}</b><small>{item.english}</small></span><ArrowRightOutlined/></button>)}</div><p className="capability-description" aria-live="polite">{capabilities[capability].description}</p></section>
  <footer className="login-footer"><span>视界 · AI 网站质量测试平台</span><span>让更好的网站，被更多人看见。</span><span>SHIJIE / V0.9</span></footer>
  </Layout></ConfigProvider>
}
