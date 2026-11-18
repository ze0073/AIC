import { Alert, Button } from 'antd'
import type { AnalysisTrace, Evidence, Finding, FindingReview, Patch, Project, Scan, Version } from './types'

export const conclusion = (status: string) => ({NEEDS_REVIEW:'自动候选',CONFIRMED:'已记录风险',DISMISSED:'已驳回'}[status] || '确定性问题')
export type Pair = {before?:Finding;after?:Finding;kind:string;reviewChanged:boolean;evidenceChanged:boolean}
export interface Comparison {
  before:Scan;after:Scan;comparable:boolean;reasons:string[];pairs:Pair[]
  notRedetected?:number;introduced?:number;remaining?:number;reviewChanged:number;verifiedFindingIds:string[]
  evidences:Record<string,Evidence[]>;traces:AnalysisTrace[];patches:Patch[]
  sourceDom?:Record<string,{before:string;after:string}>
}
export interface Summary {snapshotId:string;contentHash:string;generatedAt:string}
export interface Frozen extends Summary {report:{project:Project;version:Version;scan:Scan;findings:Finding[];patches:Patch[];reviews:Record<string,FindingReview[]>;traces:AnalysisTrace[];formula:string;disclaimer:string;comparison?:{details:Comparison}}}
export interface DemoState {project:Project;version?:Version;scan?:Scan;steps:{key:string;title:string;status:string}[];reviewFindingId?:string;fixFindingId?:string;patches:Patch[];snapshots:Summary[];beforeScanId?:string;afterScanId?:string}

export function QueryProblem({error,retry}:{error:unknown;retry:()=>void}) {
  return <Alert type="error" showIcon title="暂时无法加载" description={error instanceof Error?error.message:'请求未完成'} action={<Button onClick={retry}>重试</Button>}/>
}
