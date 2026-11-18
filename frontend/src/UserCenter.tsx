import {useEffect, useRef, useState} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {useNavigate, useSearchParams} from 'react-router-dom'
import {Alert, App, Avatar, Button, Card, Descriptions, Dropdown, Form, Input, Modal, Space, Spin, Tag, Typography, Upload} from 'antd'
import {DownOutlined, LockOutlined, LogoutOutlined, UploadOutlined, UserOutlined} from '@ant-design/icons'
import {request,resetCsrf} from './api'
import type {AuthUser, UserProfile} from './types'
import './user-center.css'

type ProfileFields = Pick<UserProfile, 'displayName'|'email'|'phone'|'bio'>
type Quota = {projectLimit:number; environmentLimit:number; buildLimit:number}
export const profileKey = (userId:string) => ['auth-profile', userId]
export function useProfile(user:AuthUser) {
  return useQuery({queryKey:profileKey(user.id), queryFn:({signal})=>request<UserProfile>('/auth/profile', {signal})})
}
export function ProfileMenu({user, onLogout, loggingOut}:{user:AuthUser; onLogout:()=>void; loggingOut:boolean}) {
  const profile = useProfile(user), navigate = useNavigate()
  return <Dropdown trigger={['click']} menu={{items:[
    {key:'profile', label:'用户中心', icon:<UserOutlined/>},
    {key:'password', label:'修改密码', icon:<LockOutlined/>},
    {type:'divider'},
    {key:'logout', label:'退出', icon:<LogoutOutlined/>, disabled:loggingOut},
  ],onClick:({key})=>{if(key==='logout') onLogout(); else navigate(key==='password'?'/profile?password=1':'/profile')}}}>
    <Button type="text" className="profile-menu-trigger" aria-label="打开用户菜单" loading={loggingOut}><Avatar size={28} src={profile.data?.avatarUrl} alt="个人头像">{Array.from(user.displayName)[0]||<UserOutlined/>}</Avatar><span className="profile-menu-name">{user.displayName}</span><DownOutlined/></Button>
  </Dropdown>
}
const fields = (profile:UserProfile):ProfileFields => ({displayName:profile.displayName, email:profile.email, phone:profile.phone, bio:profile.bio})
const normalize = (value:ProfileFields):ProfileFields => ({displayName:(value.displayName||'').trim(), email:(value.email||'').trim(), phone:(value.phone||'').trim(), bio:(value.bio||'').trim()})
export function validPhone(value:string) {
  const phone = value.trim(), digits = phone.replace(/[^0-9]/g, '')
  return !phone || (/^\+?[0-9 ()-]+$/.test(phone) && digits.length >= 7 && digits.length <= 15)
}

export function UserCenterPage({user}:{user:AuthUser}) {
  const {message, modal} = App.useApp(), cache = useQueryClient()
  const profile = useProfile(user)
  const quota = useQuery({queryKey:['auth-quota', user.id], queryFn:({signal})=>request<Quota>('/auth/quota', {signal})})
  const [form] = Form.useForm<ProfileFields>(), [passwordForm] = Form.useForm()
  const [dirty, setDirty] = useState(false), [file, setFile] = useState<File>(), [preview, setPreview] = useState<string>()
  const [passwordOpen, setPasswordOpen] = useState(false)
  const [searchParams, setSearchParams] = useSearchParams()
  const initialized = useRef(false)

  useEffect(()=>{
    if (profile.data && !dirty) {form.setFieldsValue(fields(profile.data)); initialized.current = true}
  },[profile.data, form, dirty])
  useEffect(()=>{
    if (!file) {setPreview(undefined); return}
    const url = URL.createObjectURL(file); setPreview(url)
    return ()=>URL.revokeObjectURL(url)
  },[file])
  useEffect(()=>{
    if (!dirty && !file) return
    const warn = (event:BeforeUnloadEvent)=>{event.preventDefault(); event.returnValue = ''}
    window.addEventListener('beforeunload', warn)
    return ()=>window.removeEventListener('beforeunload', warn)
  },[dirty, file])
  useEffect(()=>{
    if (searchParams.get('password') === '1') {
      setPasswordOpen(true)
      const next = new URLSearchParams(searchParams); next.delete('password'); setSearchParams(next, {replace:true})
    }
  },[searchParams, setSearchParams])

  function acceptProfile(next:UserProfile) {
    if (cache.getQueryData<AuthUser>(['auth-me'])?.id !== user.id) return false
    cache.setQueryData(profileKey(user.id), next)
    const {id, username, displayName, enabled, createdAt, platformRole} = next
    cache.setQueryData(['auth-me'], {id, username, displayName, enabled, createdAt, platformRole})
    return true
  }
  const save = useMutation({
    mutationFn:(values:ProfileFields)=>request<UserProfile>('/auth/profile', {method:'PUT', body:JSON.stringify(normalize(values))}),
    onSuccess:next=>{
      if (!acceptProfile(next)) return
      form.setFieldsValue(fields(next)); setDirty(false)
      void cache.invalidateQueries({queryKey:['platform-users']}); void cache.invalidateQueries({queryKey:['members']})
      message.success('个人资料已保存')
    }, onError:(error:Error)=>{message.error(error.message)},
  })
  const avatar = useMutation({
    mutationFn:async(selected:File|undefined)=>{
      if (!selected) return request<UserProfile>('/auth/avatar', {method:'DELETE'})
      const body = new FormData(); body.append('file', selected)
      return request<UserProfile>('/auth/avatar', {method:'POST', body})
    }, onSuccess:next=>{if (acceptProfile(next)) {setFile(undefined); message.success(next.avatarUrl?'头像已保存':'头像已移除')}},
    onError:(error:Error)=>{message.error(error.message)},
  })
  const password = useMutation({
    mutationFn:({currentPassword, newPassword}:{currentPassword:string; newPassword:string})=>request<void>('/auth/password', {method:'POST', body:JSON.stringify({currentPassword, newPassword})}),
    onSuccess:async()=>{setPasswordOpen(false); passwordForm.resetFields();message.success('密码已更新，请重新登录');await cache.cancelQueries({predicate:q=>q.queryKey[0]!=='auth-me'});resetCsrf();cache.removeQueries({predicate:q=>q.queryKey[0]!=='auth-me'});cache.setQueryData(['auth-me'],null)},
    onError:(error:Error)=>{message.error(error.message)},
  })
  const busy = save.isPending || avatar.isPending
  function discard() {
    const reset = ()=>{if(profile.data) form.setFieldsValue(fields(profile.data)); setDirty(false)}
    if (dirty) modal.confirm({title:'放弃未保存的资料修改？', content:'资料会恢复为最近保存的内容。', okText:'放弃修改', cancelText:'继续编辑', onOk:reset})
    else reset()
  }

  return <div className="user-center">
    <div className="page-head"><div><Typography.Title level={2}>用户中心</Typography.Title><Typography.Paragraph type="secondary">管理你的个人资料、账号安全与使用额度</Typography.Paragraph></div><span className="profile-page-mark">MY ACCOUNT / 01</span></div>
    <div className="user-center-grid">
      <Card title="个人资料" className="profile-editor">
        {profile.isPending?<div className="profile-loading"><Spin aria-label="加载个人资料"/></div>:profile.isError?<Alert type="error" showIcon title="个人资料加载失败" description={profile.error.message} action={<Button onClick={()=>void profile.refetch()}>重试</Button>}/>:<>
          <div className="profile-avatar-editor">
            <Avatar size={80} src={preview||profile.data.avatarUrl} alt="个人头像">{Array.from(profile.data.displayName)[0]||<UserOutlined/>}</Avatar>
            <div><Typography.Text strong>个人头像</Typography.Text><p className="profile-help">PNG 或 JPEG，最大 2 MB。保存时居中裁剪为正方形。</p>
              <Space wrap>
                <Upload accept="image/png,image/jpeg" maxCount={1} showUploadList={false} disabled={busy} beforeUpload={selected=>{
                  if (!['image/png','image/jpeg'].includes(selected.type)) {message.error('请选择 PNG 或 JPEG 图片'); return Upload.LIST_IGNORE}
                  if (selected.size > 2*1024*1024 || selected.size === 0) {message.error('头像文件大小需在 2 MB 以内'); return Upload.LIST_IGNORE}
                  setFile(selected); return false
                }}><Button icon={<UploadOutlined/>} disabled={busy}>选择头像</Button></Upload>
                {file&&<><Button type="primary" loading={avatar.isPending} disabled={save.isPending} onClick={()=>avatar.mutate(file)}>上传并保存头像</Button><Button disabled={busy} onClick={()=>setFile(undefined)}>取消选择</Button></>}
                {profile.data.avatarUrl&&!file&&<Button disabled={busy} onClick={()=>modal.confirm({title:'移除当前头像？', okText:'移除', cancelText:'取消', onOk:()=>avatar.mutateAsync(undefined)})}>移除头像</Button>}
              </Space>{file&&<p className="profile-help" role="status">已选择：{file.name}，尚未保存</p>}
            </div>
          </div>
          <Form form={form} layout="vertical" disabled={busy} onValuesChange={(_,values)=>{if(initialized.current && profile.data) setDirty(JSON.stringify(normalize(values)) !== JSON.stringify(fields(profile.data)))}} onFinish={values=>save.mutate(values)}>
            <Form.Item name="displayName" label="显示名称" rules={[{required:true, whitespace:true, message:'请输入显示名称'},{max:120,message:'显示名称最多 120 字符'}]}><Input maxLength={120} autoComplete="nickname" placeholder="让团队成员认识你"/></Form.Item>
            <div className="profile-contact-grid">
              <Form.Item name="email" label="联系邮箱" extra="仅作联系资料，不用于登录或找回密码" rules={[{type:'email', transform:(value:string)=>value?.trim(), message:'请输入有效邮箱'},{max:254,message:'邮箱最多 254 字符'}]}><Input maxLength={254} autoComplete="email" placeholder="选填"/></Form.Item>
              <Form.Item name="phone" label="手机号" extra="仅作联系资料，不用于登录或找回密码" rules={[{validator:(_,value:string)=>validPhone(value||'')?Promise.resolve():Promise.reject(new Error('请输入有效手机号，可包含国际区号、空格和连字符'))},{max:32,message:'手机号最多 32 字符'}]}><Input maxLength={32} autoComplete="tel" placeholder="选填，支持国际区号"/></Form.Item>
            </div>
            <Form.Item name="bio" label="个人简介" rules={[{max:500,message:'个人简介最多 500 字符'}]}><Input.TextArea maxLength={500} showCount rows={4} placeholder="介绍一下自己（选填）"/></Form.Item>
            <div className="profile-form-footer"><Space wrap><Button type="primary" htmlType="submit" loading={save.isPending} disabled={!dirty||avatar.isPending}>保存资料</Button><Button disabled={!dirty||busy} onClick={discard}>取消修改</Button></Space><span className="profile-help" role="status">{dirty?'有未保存的修改':'资料已同步'}</span></div>
          </Form>
        </>}
      </Card>
      <aside className="profile-sidebar" aria-label="账号信息与使用额度">
        <Card title="账号信息"><Descriptions column={1} size="small" items={[
          {key:'username',label:'用户名',children:user.username},
          {key:'role',label:'平台身份',children:<Tag>{user.platformRole==='ADMIN'?'平台管理员':'用户'}</Tag>},
          {key:'status',label:'账号状态',children:<Tag color={user.enabled?'green':'default'}>{user.enabled?'正常':'已停用'}</Tag>},
          {key:'createdAt',label:'注册时间',children:user.createdAt?.replace('T',' ').slice(0,16)},
        ]}/><p className="profile-help">用户名为固定的登录账号。</p></Card>
        <Card title="账号安全"><div className="profile-security"><span className="profile-security-icon"><LockOutlined/></span><div><Typography.Text strong>登录密码</Typography.Text><p className="profile-help">通过当前密码验证后设置新密码。</p></div></div><Button block onClick={()=>setPasswordOpen(true)}>修改密码</Button></Card>
        <Card title="使用额度">{quota.isPending?<Spin aria-label="加载使用额度"/>:quota.isError?<Alert type="error" title="额度加载失败" action={<Button size="small" onClick={()=>void quota.refetch()}>重试</Button>}/>:<div className="profile-quota">{[
          ['项目数量上限',quota.data.projectLimit,'个'],['运行环境数量上限',quota.data.environmentLimit,'个'],['同时构建任务上限',quota.data.buildLimit,'个'],
        ].map(([label,value,unit])=><div key={label}><span>{label}</span><b>{value}<small>{unit}</small></b></div>)}</div>}<p className="profile-help">需要更多额度？联系平台管理员调整。</p></Card>
      </aside>
    </div>
    <Modal title="修改登录密码" open={passwordOpen} onCancel={()=>{if(!password.isPending){setPasswordOpen(false); passwordForm.resetFields()}}} onOk={()=>passwordForm.submit()} confirmLoading={password.isPending} cancelButtonProps={{disabled:password.isPending}} okText="保存新密码" destroyOnHidden>
      <Form form={passwordForm} layout="vertical" disabled={password.isPending} onFinish={values=>password.mutate(values)}>
        <Form.Item name="currentPassword" label="当前密码" rules={[{required:true,message:'请输入当前密码'}]}><Input.Password autoComplete="current-password"/></Form.Item>
        <Form.Item name="newPassword" label="新密码" rules={[{required:true,message:'请输入新密码'},{min:10,max:200,message:'新密码长度需为 10–200 位'}]}><Input.Password autoComplete="new-password" maxLength={200}/></Form.Item>
        <Form.Item name="confirmPassword" label="确认新密码" dependencies={['newPassword']} rules={[{required:true,message:'请再次输入新密码'},{validator:(_,value)=>value===passwordForm.getFieldValue('newPassword')?Promise.resolve():Promise.reject(new Error('两次输入的新密码不一致'))}]}><Input.Password autoComplete="new-password" maxLength={200}/></Form.Item>
      </Form>
    </Modal>
  </div>
}
