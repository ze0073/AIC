import { useEffect, useState, type ReactNode } from "react";
import { Alert, Button, Modal, Skeleton, Tag } from "antd";
import { ArrowRightOutlined, BulbOutlined, CheckCircleOutlined, FileSearchOutlined, ReloadOutlined, SafetyCertificateOutlined, SearchOutlined, ToolOutlined } from "@ant-design/icons";
import { Link } from "react-router-dom";
import { ApiError, request } from "./api";

type ExplanationIssue = { id:string; title:string; description?:string; source:string; severity?:string; runId:string };
export type IssueExplanation = { mode:string; phenomenon:string; possibleCauses:string[]; evidenceBasis:string[]; fixSuggestions:string[]; verificationSteps:string[]; unavailableReason?:string };
type AnalysisState = { status:"loading" } | { status:"success"; result:IssueExplanation } | { status:"error"; message:string };
const sourceNames:Record<string,string> = { FRONTEND:"页面体验", AXE:"页面体验", SECURITY:"接口与权限", ZAP:"接口与权限", API:"接口与权限", PERFORMANCE:"承载能力", K6:"承载能力" };
const sectionLinks = [
  { id:"explanation-causes", label:"可能原因", icon:<SearchOutlined/> },
  { id:"explanation-evidence", label:"证据依据", icon:<FileSearchOutlined/> },
  { id:"explanation-fixes", label:"修复建议", icon:<ToolOutlined/> },
  { id:"explanation-verification", label:"验证步骤", icon:<CheckCircleOutlined/> },
];

export function IssueExplanationModal({ issue, onClose }:{ issue?:ExplanationIssue; onClose:()=>void }) {
  const [state,setState] = useState<AnalysisState>({ status:"loading" });
  const [attempt,setAttempt] = useState({ sequence:0, useAi:true });
  useEffect(() => {
    if (!issue) return;
    const controller = new AbortController();
    setState({ status:"loading" });
    request<IssueExplanation>(`/issues/${issue.id}/ai-explanation${attempt.useAi?"":"?useAi=false"}`, {
      method:"POST", signal:controller.signal, timeoutMs:25_000,
    }).then(result => {
      if (!controller.signal.aborted) setState({ status:"success", result });
    }).catch(error => {
      if (!controller.signal.aborted) setState({ status:"error", message:error instanceof ApiError && error.code==="REQUEST_TIMEOUT" ? "本次分析等待时间较长。可以重试，或先查看基于已有证据的规则解释。" : (error as Error).message || "暂时无法读取原因解释，请稍后重试。" });
    });
    return () => controller.abort();
  }, [issue?.id, attempt]);
  const run = (useAi:boolean) => setAttempt(current => ({ sequence:current.sequence+1, useAi }));
  const close = () => { onClose(); setAttempt({ sequence:0, useAi:true }); setState({ status:"loading" }); };
  const result = state.status==="success" ? state.result : undefined;

  return <Modal title={<span className="explanation-modal-title"><BulbOutlined/> 看懂这个问题</span>} open={!!issue} onCancel={close} width={1080} className="issue-explanation-modal" footer={<div className="explanation-footer"><span><SafetyCertificateOutlined/> 建议需结合实际业务验证</span><div>{state.status==="loading" ? <Button onClick={()=>run(false)} disabled={!attempt.useAi}>先看规则解释</Button> : <Button icon={<ReloadOutlined/>} onClick={()=>run(true)}>重新分析</Button>}{issue&&<Link to={`/issues/${issue.id}/repair`}><Button type="primary" icon={<ToolOutlined/>}>开始修复</Button></Link>}<Button onClick={close}>{state.status==="loading"?"取消等待":"关闭"}</Button></div></div>}>
    {issue&&<div className="issue-explanation-body">
      <section className="explanation-overview">
        <span className="explanation-overview-icon" aria-hidden="true"><BulbOutlined/></span>
        <div><div className="explanation-overview-meta"><span>问题解读</span><Tag>{sourceNames[issue.source]||"网站质量"}</Tag>{issue.severity&&<Tag color={issue.severity==="HIGH"?"red":issue.severity==="MEDIUM"?"orange":"blue"}>{issue.severity==="HIGH"?"高优先级":issue.severity==="MEDIUM"?"中优先级":"低优先级"}</Tag>}{result&&<Tag color={result.mode==="AI"?"cyan":"default"}>{result.mode==="AI"?"AI 辅助解释":"基于检查规则"}</Tag>}</div><h2>{issue.title}</h2>{(result?.phenomenon||issue.description)&&<p>{result?.phenomenon||issue.description}</p>}</div>
        <Link className="explanation-evidence-link" to={`/test-runs/${issue.runId}`}><FileSearchOutlined/> 查看原始证据 <ArrowRightOutlined/></Link>
      </section>

      <nav className="explanation-flow" aria-label="问题分析流程">{sectionLinks.map((section,index)=><button key={section.id} type="button" disabled={!result} onClick={()=>document.getElementById(section.id)?.scrollIntoView({ behavior:"smooth", block:"nearest" })}><span className="explanation-flow-icon" aria-hidden="true">{section.icon}</span><span><small>0{index+1}</small><b>{section.label}</b></span>{index<sectionLinks.length-1&&<ArrowRightOutlined className="explanation-flow-arrow"/>}</button>)}</nav>

      {state.status==="loading"&&<div role="status" aria-live="polite" className="explanation-loading"><span className="explanation-loading-orbit" aria-hidden="true"><FileSearchOutlined/></span><div><b>{attempt.useAi?"正在整理原因与证据…":"正在读取规则解释…"}</b><p>{attempt.useAi?"在线分析需要一点时间，超时后会显示明确提示。也可以先看规则解释。":"根据保存的问题和检查规则整理处理建议。"}</p></div></div>}
      {state.status==="loading"&&<div className="explanation-card-grid" aria-hidden="true">{sectionLinks.map(section=><section className="explanation-section" key={section.id}><header><span>{section.icon}</span><h3>{section.label}</h3></header><Skeleton active paragraph={{rows:2}} title={false}/></section>)}</div>}
      {state.status==="error"&&<div className="explanation-error"><Alert type="warning" showIcon title="这次解释暂时没有完成" description={state.message}/><div><Button type="primary" icon={<ReloadOutlined/>} onClick={()=>run(true)}>重试分析</Button><Button onClick={()=>run(false)}>查看规则解释</Button></div></div>}
      {result&&<>
        {result.mode!=="AI"&&<div className="explanation-rule-note"><SafetyCertificateOutlined/><span>本次根据检查规则整理建议。</span>{result.unavailableReason&&<details><summary>查看说明</summary><p>{result.unavailableReason}</p></details>}</div>}
        <div className="explanation-card-grid">
          <ExplanationSection id="explanation-causes" tone="causes" title="可能原因" subtitle="这些是待验证的推测" icon={<SearchOutlined/>} items={result.possibleCauses}/>
          <ExplanationSection id="explanation-evidence" tone="evidence" title="证据依据" subtitle="从检测结果中寻找线索" icon={<FileSearchOutlined/>} items={result.evidenceBasis}/>
          <ExplanationSection id="explanation-fixes" tone="fixes" title="修复建议" subtitle="按最小范围逐步处理" icon={<ToolOutlined/>} items={result.fixSuggestions}/>
          <ExplanationSection id="explanation-verification" tone="verification" title="验证步骤" subtitle="复测确认问题是否解决" icon={<CheckCircleOutlined/>} items={result.verificationSteps}/>
        </div>
        <p className="explanation-boundary"><SafetyCertificateOutlined/> 原因属于分析建议；是否存在漏洞、是否修复完成，以实际证据和复测结果为准。</p>
      </>}
    </div>}
  </Modal>;
}

function ExplanationSection({ id,tone,title,subtitle,icon,items }:{ id:string;tone:string;title:string;subtitle:string;icon:ReactNode;items:unknown }) {
  const values = Array.isArray(items) ? items.filter((item):item is string=>typeof item==="string"&&!!item.trim()) : [];
  return <section id={id} className={`explanation-section ${tone}`} aria-labelledby={`${id}-title`}>
    <header><span aria-hidden="true">{icon}</span><div><h3 id={`${id}-title`}>{title}</h3><p>{subtitle}</p></div><small>{values.length} 项</small></header>
    {values.length ? <ol>{values.map((item,index)=><li key={index}><span className="explanation-item-marker" aria-hidden="true">{tone==="evidence"?<FileSearchOutlined/>:String(index+1).padStart(2,"0")}</span><p>{item}</p></li>)}</ol> : <p className="explanation-empty">当前证据未提供这一部分，可先查看原始证据再补充判断。</p>}
  </section>;
}
