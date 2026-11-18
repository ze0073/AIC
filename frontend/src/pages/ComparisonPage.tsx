import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { Alert, Card, Checkbox, Col, Empty, Row, Select, Space, Spin, Table, Tag, Typography } from 'antd'
import { request } from '../api'
import type { Finding, Scan } from '../types'
import { QueryProblem, conclusion } from '../workflowShared'
import type { Comparison, Pair, DemoState } from '../workflowShared'

export function ComparisonEvidence({data,page}:{data:Comparison;page:string}) {
  const panes=useRef<(HTMLDivElement|null)[]>([]),[sync,setSync]=useState(true),[zoom,setZoom]=useState(100)
  const [broken,setBroken]=useState<Record<string,boolean>>({})
  const sides=[data.before,data.after]
  return <><Space wrap><Checkbox checked={sync} onChange={e=>setSync(e.target.checked)}>同步滚动</Checkbox><Select aria-label="证据缩放" value={zoom} onChange={setZoom} options={[50,100,150,200].map(value=>({value,label:`${value}%`}))}/></Space>
    <Row gutter={16}>{sides.map((scan,i)=>{
      const items=data.pairs.map(p=>i===0?p.before:p.after).filter((f):f is Finding=>!!f&&f.pagePath===page)
      const evidence=items.flatMap(f=>data.evidences[f.id]||[])
      const artifact=data.traces.find(t=>t.scanTaskId===scan.id&&t.pagePath===page&&t.artifactId)?.artifactId||evidence.find(e=>e.artifactId)?.artifactId
      return <Col xs={24} lg={12} key={scan.id+i}><Card title={i===0?'原扫描证据':'复测证据'}>
        <div className="comparison-pane" tabIndex={0} role="region" aria-label={i===0?'原扫描截图':'复测截图'} ref={el=>{panes.current[i]=el}} onScroll={e=>{
          if(!sync)return;const from=e.currentTarget,to=panes.current[1-i];if(!to)return
          const top=from.scrollTop/Math.max(1,from.scrollHeight-from.clientHeight)*(to.scrollHeight-to.clientHeight)
          const left=from.scrollLeft/Math.max(1,from.scrollWidth-from.clientWidth)*(to.scrollWidth-to.clientWidth)
          if(Math.abs(to.scrollTop-top)>1)to.scrollTop=top
          if(Math.abs(to.scrollLeft-left)>1)to.scrollLeft=left
        }}>{artifact&&!broken[artifact]?<img style={{width:`${zoom}%`,maxWidth:'none'}} src={`/api/artifacts/${artifact}`} alt={`${i===0?'原扫描':'复测'} ${page} 完整页面证据`} onError={()=>setBroken(b=>({...b,[artifact]:true}))}/>:<Empty description="此页面截图证据缺失"/>}</div>
        <Typography.Title level={5}>相关 DOM 与文字证据</Typography.Title>
        {data.pairs.filter(p=>(p.before||p.after)?.pagePath===page).map(p=>{const target=(p.before||p.after)!;const source=data.sourceDom?.[target.id];return source&&<details key={target.id}><summary>{target.ruleId} · {target.selector} · 不可变版本源码摘要</summary><pre className="json-view">{i===0?source.before:source.after}</pre></details>})}
        {evidence.length?evidence.filter(e=>e.kind!=='AI').map(e=>{let html='';try{html=JSON.parse(e.payload).outerHtml||''}catch{}return <div key={e.id}><p>{e.summary}</p><pre className="json-view">{html||e.payload}</pre></div>}):<Typography.Paragraph>此侧没有对应问题证据；请结合补丁 Diff 和页面截图复核，不能据此推断元素已删除。</Typography.Paragraph>}
      </Card></Col>
    })}</Row></>
}

const preventionByRule:Record<string,string>={
  R001:'把图片替代文本设为组件必填属性；装饰图统一写空 alt，并在提交前扫描图片组件。',
  R002:'表单组件默认生成可见 label 和稳定关联 ID；评审时同时检查视觉标签与可访问名称。',
  R003:'图标按钮和空链接必须在公共组件层提供可访问名称，组件测试同时断言名称和用途。',
  R004:'在页面模板统一设置合法 lang，并在构建检查中阻止缺失或非法语言代码。',
  R005:'由页面骨架管理唯一主标题和连续层级，内容组件不要按字号自行选择标题级别。',
  R006:'把文字和背景色收敛到经过对比度验证的设计令牌，主题变更后自动跑对比度检查。',
  R007:'交互组件必须支持键盘聚焦和清晰焦点样式；合并前跑正向、反向 Tab 路径回归。',
  R008:'由组件生成唯一 ID，改动 ARIA 引用时同步验证目标是否存在，避免手工复制固定 ID。',
  R009:'让 DOM 顺序与视觉阅读顺序保持一致；布局调整后复核桌面和移动端的阅读轨迹。',
};

function diffStats(diff:string){
  const lines=diff.split(/\r?\n/);
  return {added:lines.filter(line=>line.startsWith('+')&&!line.startsWith('+++')).length,removed:lines.filter(line=>line.startsWith('-')&&!line.startsWith('---')).length};
}

function pairLabel(pair:Pair){
  const finding=pair.before||pair.after;
  return finding?`${finding.ruleId} · ${finding.title}（${finding.pagePath} · ${finding.selector}）`:'未识别问题';
}

export function RepairNarrative({data}:{data:Comparison}){
  const fixed=data.pairs.filter(pair=>pair.kind==='NOT_REDETECTED');
  const remaining=data.pairs.filter(pair=>pair.kind==='REMAINING');
  const introduced=data.pairs.filter(pair=>pair.kind==='INTRODUCED');
  const changedFiles=Array.from(new Set(data.patches.map(patch=>patch.targetFile)));
  const rules=Array.from(new Set([...fixed,...remaining,...introduced].map(pair=>(pair.before||pair.after)?.ruleId).filter((rule):rule is string=>!!rule)));
  const prevention=rules.map(rule=>({rule,text:preventionByRule[rule]||'把该规则加入提交前扫描和固定回归套件，并保存本次证据作为后续基线。'}));
  const resultTitle=!data.comparable?'本次只能解释代码与证据变化，不能判断改善':introduced.length||remaining.length
    ?`修复已产生效果，但仍有 ${remaining.length} 项存量问题和 ${introduced.length} 项新增问题`
    :fixed.length?`本次修复后 ${fixed.length} 项原问题未再检出`:'两次扫描没有发现可归因的检出变化';
  return <Card className="repair-narrative" title={<Space wrap><Tag color="purple">智能体讲解</Tag><span>这次修复发生了什么</span></Space>}>
    <Alert showIcon type={!data.comparable?'warning':introduced.length?'warning':fixed.length?'success':'info'} title={resultTitle}
      description={data.comparable?'讲解依据为补丁 Diff、同范围两次扫描和保存的证据；“未再检出”只代表当前规则与范围。':data.reasons.join('；')}/>
    <Row gutter={[14,14]} className="repair-narrative-grid">
      <Col xs={24} lg={12}><section><span className="repair-section-index">01</span><Typography.Title level={5}>改了什么</Typography.Title>
        {data.patches.length?<ul>{data.patches.map(patch=>{const stats=diffStats(patch.diffText);return <li key={patch.id}><strong>{patch.targetFile}</strong>：{patch.patchType==='AI_CANDIDATE'?'采用已确认的候选文本':'应用模板修复'}，新增 {stats.added} 行、移除 {stats.removed} 行{patch.candidateText?`；采用内容“${patch.candidateText}”`:''}。</li>})}</ul>:<Typography.Paragraph type="secondary">没有关联到已保存的补丁记录；以下讲解仅依据两次扫描结果。</Typography.Paragraph>}
        {fixed.slice(0,5).map(pair=><Typography.Paragraph key={pair.before?.id} className="repair-finding-line">✓ {pairLabel(pair)} 未再检出</Typography.Paragraph>)}
      </section></Col>
      <Col xs={24} lg={12}><section><span className="repair-section-index">02</span><Typography.Title level={5}>为什么这样改</Typography.Title>
        {fixed.length?<ul>{fixed.slice(0,5).map(pair=><li key={pair.before?.id}>{pair.before?.description||pair.before?.title} 修复直接落在原问题对应的元素或页面规则上。</li>)}</ul>:<Typography.Paragraph type="secondary">当前没有“原问题未再检出”的证据，暂时不能把变化归因于修复。</Typography.Paragraph>}
        {changedFiles.length>0&&<Typography.Paragraph>改动集中在 {changedFiles.join('、')}，便于审阅、回退和针对性复测。</Typography.Paragraph>}
      </section></Col>
      <Col xs={24} lg={12}><section><span className="repair-section-index">03</span><Typography.Title level={5}>这样改的好处</Typography.Title>
        <ul>
          <li>{fixed.length?`${fixed.length} 项原检出在相同扫描范围内消失，减少了对应的可访问性风险。`:'保留了可核对的补丁和证据链，方便继续定位。'}</li>
          <li>{data.verifiedFindingIds.length?`${data.verifiedFindingIds.length} 项目标规则通过自动复测，结果可追溯到原问题。`:'当前没有目标规则通过自动复测，不能宣称修复已验证。'}</li>
          <li>{remaining.length===0&&introduced.length===0?'没有观察到存量问题或新增回归。':`仍存在 ${remaining.length} 项、并新增 ${introduced.length} 项，已明确列入下一步处理。`}</li>
        </ul>
      </section></Col>
      <Col xs={24} lg={12}><section><span className="repair-section-index">04</span><Typography.Title level={5}>下一次如何避免</Typography.Title>
        {prevention.length?<ul>{prevention.map(item=><li key={item.rule}><Tag>{item.rule}</Tag>{item.text}</li>)}</ul>:<Typography.Paragraph>把本次扫描配置固定为回归基线，在每次发布前执行同范围检测。</Typography.Paragraph>}
      </section></Col>
    </Row>
    <div className="repair-recap"><Typography.Text strong>给团队的总结</Typography.Text><Typography.Paragraph>
      本次共关联 {data.patches.length} 个补丁、{fixed.length} 项未再检出、{remaining.length} 项仍存在、{introduced.length} 项新增。后续把对应规则固化到公共组件、代码评审清单和发布前回归中；若页面范围或规则版本变化，先建立新基线再比较。
    </Typography.Paragraph></div>
  </Card>;
}

export function ComparisonPage() {
  const {projectId=''}=useParams(),[params,setParams]=useSearchParams()
  const before=params.get('beforeScanId')||'',after=params.get('afterScanId')||''
  const scans=useQuery({queryKey:['project-scans',projectId],queryFn:()=>request<Scan[]>(`/projects/${projectId}/scans`)})
  const defaults=useQuery({queryKey:['demo-default',projectId],queryFn:()=>request<DemoState>(`/projects/${projectId}/demo-state`),enabled:!before&&!after})
  useEffect(()=>{if(!before&&!after&&defaults.data?.beforeScanId&&defaults.data.afterScanId){setParams({beforeScanId:defaults.data.beforeScanId,afterScanId:defaults.data.afterScanId},{replace:true})}},[before,after,defaults.data,setParams])
  const result=useQuery({queryKey:['comparison',projectId,before,after],queryFn:()=>request<Comparison>(`/projects/${projectId}/comparison?${new URLSearchParams({beforeScanId:before,afterScanId:after})}`),enabled:!!before&&!!after})
  const set=(key:string,value:string)=>{const p=new URLSearchParams(params);p.set(key,value);p.delete('page');setParams(p)}
  const data=result.data,pages=Array.from(new Set([...(data?.pairs||[]).map(p=>(p.before||p.after)!.pagePath),...(data?.traces||[]).map(t=>t.pagePath)]))
  const page=params.get('page')||pages[0]||''
  const options=(scans.data||[]).map(s=>({value:s.id,label:`${s.createdAt} · ${s.status} · ${s.id.slice(-6)}`}))
  const origin=params.get('demo')
  const demoReturn=origin?.startsWith(`/projects/${projectId}/demo?`)?origin:`/projects/${projectId}/demo${before?`?scanId=${encodeURIComponent(before)}&step=4`:''}`
  return <><Typography.Title level={2}>修复前后证据对照</Typography.Title><Link to={demoReturn}>返回演示流程</Link>
    <Card><Space wrap><Select aria-label="原扫描" placeholder="选择原扫描" value={before||undefined} options={options} style={{width:310}} onChange={v=>set('beforeScanId',v)}/><Select aria-label="复测扫描" placeholder="选择复测扫描" value={after||undefined} options={options} style={{width:310}} onChange={v=>set('afterScanId',v)}/></Space></Card>
    {scans.isError&&<QueryProblem error={scans.error} retry={()=>scans.refetch()}/>}{result.isError&&<QueryProblem error={result.error} retry={()=>result.refetch()}/>}
    {result.isFetching&&<Spin/>}{!before||!after?<Empty description="选择同一项目的两次扫描，或先完成一次模板修复与复测"/>:null}
    {data&&<><Alert showIcon type={data.comparable?'info':'warning'} title={data.comparable?'扫描范围一致，可比较检出结果':'不可直接比较，不计算改善数量'} description={data.comparable?'可选审阅变化单独展示；未再检出不等于完整合规。':data.reasons.join('；')}/>
      {data.comparable&&<Card><Space wrap><Tag>未再检出 {data.notRedetected}</Tag><Tag>仍存在 {data.remaining}</Tag><Tag>新增 {data.introduced}</Tag><Tag>复核结论变化 {data.reviewChanged}</Tag><Tag>目标基础规则修复通过 {data.verifiedFindingIds.length}</Tag></Space></Card>}
      <RepairNarrative data={data}/>
      <Select aria-label="对照页面" style={{minWidth:240}} value={page||undefined} options={pages.map(value=>({value,label:value}))} onChange={v=>{const p=new URLSearchParams(params);p.set('page',v);setParams(p)}}/>
      <ComparisonEvidence data={data} page={page}/>
      <Card title="逐条结果"><Table rowKey={p=>(p.before?.id||'')+(p.after?.id||'')} dataSource={data.pairs.filter(p=>(p.before||p.after)?.pagePath===page)} columns={[
        {title:'元素',render:(_,p:Pair)=><span>{(p.before||p.after)?.ruleId} · {(p.before||p.after)?.selector}</span>},
        {title:'检出变化',render:(_,p:Pair)=>data.comparable?({NOT_REDETECTED:'未再检出',INTRODUCED:'新增',REMAINING:'仍存在'}[p.kind]):'仅展示证据'},
        {title:'结论',render:(_,p:Pair)=>`${p.before?conclusion(p.before.status):'无'} → ${p.after?conclusion(p.after.status):'无'}`},
        {title:'说明',render:(_,p:Pair)=><Space wrap>{p.reviewChanged&&<Tag>复核结论变化</Tag>}{p.evidenceChanged&&<Tag>证据变化</Tag>}{p.before&&data.verifiedFindingIds.includes(p.before.id)&&<Tag color="green">基础规则修复通过</Tag>}</Space>},
      ]}/></Card>{data.patches.map(p=><Card key={p.id} title={`补丁 Diff · ${p.targetFile}`}><pre className="json-view">{p.diffText}</pre><Link to={`/fix-patches/${p.id}`}>查看补丁记录</Link></Card>)}</>}
  </>
}
