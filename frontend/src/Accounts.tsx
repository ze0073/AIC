import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {App,Alert,Button,Card,Form,Input,InputNumber,Modal,Space,Table,Tag} from 'antd'
import {request} from './api'
import type {AuthUser} from './types'
import {VisualBanner} from './VisualBanner'
type Quota={projectLimit:number;environmentLimit:number;buildLimit:number}
export function AccountsPage(){
  const {message,modal}=App.useApp(),cache=useQueryClient()
  const [selected,setSelected]=useState<AuthUser>(),[passwordUser,setPasswordUser]=useState<AuthUser>(),[createOpen,setCreateOpen]=useState(false)
  const [form]=Form.useForm(),[createForm]=Form.useForm(),[passwordForm]=Form.useForm()
  const users=useQuery({queryKey:['platform-users'],queryFn:({signal})=>request<AuthUser[]>('/users',{signal})})
  const me=cache.getQueryData<AuthUser>(['auth-me'])
  const quota=useMutation({mutationFn:async(user:AuthUser)=>{const q=await request<Quota>(`/users/${user.id}/quota`);form.setFieldsValue(q);setSelected(user)},onError:(e:Error)=>message.error(e.message)})
  const save=useMutation({mutationFn:(values:Quota)=>request(`/users/${selected?.id}/quota`,{method:'POST',body:JSON.stringify(values)}),onSuccess:()=>{setSelected(undefined);message.success('使用额度已更新')},onError:(e:Error)=>message.error(e.message)})
  const create=useMutation({mutationFn:(values:object)=>request('/users',{method:'POST',body:JSON.stringify(values)}),onSuccess:()=>{setCreateOpen(false);createForm.resetFields();void cache.invalidateQueries({queryKey:['platform-users']})},onError:(e:Error)=>message.error(e.message)})
  const status=useMutation({mutationFn:(user:AuthUser)=>request(`/users/${user.id}/status`,{method:'POST',body:JSON.stringify({enabled:!user.enabled})}),onSuccess:()=>{void cache.invalidateQueries({queryKey:['platform-users']});message.success('账号状态已更新')},onError:(e:Error)=>message.error(e.message)})
  const password=useMutation({mutationFn:({newPassword}:{newPassword:string})=>request(`/users/${passwordUser?.id}/password`,{method:'POST',body:JSON.stringify({newPassword})}),onSuccess:()=>{setPasswordUser(undefined);passwordForm.resetFields();message.success('密码已重置，旧登录已失效');void cache.invalidateQueries({queryKey:['auth-me']})},onError:(e:Error)=>message.error(e.message)})
  return <><VisualBanner theme="accounts" compact title="平台账号管理" description="管理身份、登录与使用额度，让协作有序进行。" actions={<Button type="primary" onClick={()=>setCreateOpen(true)}>添加账号</Button>}/><Card title="账号列表">
    <p>管理账号状态、登录密码和使用额度。项目成员在对应项目中管理；停用账号保留历史数据。</p>
    {users.isError?<Alert type="error" showIcon title="账号列表加载失败" description={users.error.message} action={<Button onClick={()=>void users.refetch()}>重试</Button>}/>:<Table rowKey="id" loading={users.isLoading} dataSource={users.data} columns={[
      {title:'用户',render:(_,u:AuthUser)=><Space>{u.displayName}<small>{u.username}</small></Space>},
      {title:'身份',render:(_,u:AuthUser)=><Tag>{u.platformRole==='ADMIN'?'平台管理员':'用户'}</Tag>},
      {title:'状态',render:(_,u:AuthUser)=><Tag color={u.enabled?'green':'default'}>{u.enabled?'正常':'已停用'}</Tag>},
      {title:'管理',render:(_,u:AuthUser)=><Space wrap><Button loading={quota.isPending&&quota.variables?.id===u.id} onClick={()=>quota.mutate(u)}>调整额度</Button><Button onClick={()=>{passwordForm.resetFields();setPasswordUser(u)}}>重置密码</Button><Button danger={u.enabled} disabled={u.id===me?.id||status.isPending} onClick={()=>modal.confirm({title:`${u.enabled?'停用':'启用'} ${u.displayName}？`,content:u.enabled?'该账号将无法操作平台，已有登录会失效。历史记录和数据保留。':'启用后该账号可以重新登录。',okText:u.enabled?'停用':'启用',cancelText:'取消',onOk:()=>status.mutateAsync(u)})}>{u.enabled?'停用':'启用'}</Button></Space>},
    ]} scroll={{x:680}}/>}
    <Modal title={`调整 ${selected?.displayName||''} 的额度`} open={!!selected} onCancel={()=>setSelected(undefined)} onOk={()=>form.submit()} confirmLoading={save.isPending}><Form form={form} layout="vertical" onFinish={v=>save.mutate(v)}>{[{name:'projectLimit',label:'最多创建项目',max:100},{name:'environmentLimit',label:'最多运行环境',max:20},{name:'buildLimit',label:'同时等待或执行的构建',max:4}].map(f=><Form.Item key={f.name} name={f.name} label={f.label} rules={[{required:true}]}><InputNumber min={1} max={f.max}/></Form.Item>)}</Form></Modal>
    <Modal title="添加平台账号" open={createOpen} onCancel={()=>setCreateOpen(false)} onOk={()=>createForm.submit()} confirmLoading={create.isPending}><Form form={createForm} layout="vertical" onFinish={v=>create.mutate(v)}><Form.Item name="username" label="用户名" rules={[{required:true,pattern:/^[a-z0-9._-]{3,80}$/,message:'3–80 位小写字母、数字、点、下划线或连字符'}]}><Input maxLength={80}/></Form.Item><Form.Item name="displayName" label="显示名称" rules={[{required:true,whitespace:true,max:120}]}><Input maxLength={120}/></Form.Item><Form.Item name="password" label="初始密码" rules={[{required:true,min:10,max:200}]}><Input.Password autoComplete="new-password"/></Form.Item></Form></Modal>
    <Modal title={`重置 ${passwordUser?.displayName||''} 的密码`} open={!!passwordUser} onCancel={()=>{setPasswordUser(undefined);passwordForm.resetFields()}} onOk={()=>passwordForm.submit()} confirmLoading={password.isPending}><Alert type="warning" showIcon title="所有旧登录及私有预览会失效" description="通过可信渠道将新密码交给账号本人，由本人登录后修改。"/><Form form={passwordForm} layout="vertical" onFinish={v=>password.mutate(v)}><Form.Item name="newPassword" label="新密码" rules={[{required:true,min:10,max:200}]}><Input.Password autoComplete="new-password"/></Form.Item><Form.Item name="confirmation" label="再次输入" dependencies={['newPassword']} rules={[{required:true},({getFieldValue})=>({validator:(_,v)=>v===getFieldValue('newPassword')?Promise.resolve():Promise.reject(new Error('两次密码不一致'))})]}><Input.Password autoComplete="new-password"/></Form.Item></Form></Modal>
  </Card></>
}
