export interface Project {
  id: string
  name: string
  description?: string
  currentVersionId?: string
  deletionStatus?: 'ACTIVE'|'DELETING'|'DELETE_FAILED'
  projectRole?: 'ADMIN'|'MEMBER'|'VIEWER'
  createdAt: string
  updatedAt: string
}

export interface AuthUser { id:string; username:string; displayName:string; enabled:boolean; createdAt:string; platformRole?:string }

export interface UserProfile extends AuthUser { email:string; phone:string; bio:string; avatarUrl?:string|null }

export interface OnlineTarget {
  id:string; projectId:string; name:string; baseUrl:string; allowedOrigin:string
  allowedAddresses:string[]; pathPrefixes:string[]; allowActiveScan:boolean; allowWriteTests:boolean
  cleanupInstructions?:string; createdAt:string; updatedAt:string
}

export interface TestSuite { id:string; projectId:string; name:string; description?:string; currentVersion:number; currentVersionId:string; createdAt:string; updatedAt:string }
export interface TestSuiteVersion { id:string; suiteId:string; projectId:string; versionNo:number; definition:Record<string,unknown>; createdAt:string }

export interface PerformanceAssessment {
  status:'PASSED'|'FAILED'|'INCONCLUSIVE'; reason:string; latencyAvailable:boolean;
  allRequestsFailed:boolean; thresholdsFailed:boolean; requestCount?:number;
  errorRate?:number; p95Ms?:number; p95LimitMs:number; errorRateLimit:number
}
export interface RunModule { id:string; moduleType:string; status:string; summary?:string; resultPayload?:string; startedAt?:string; finishedAt?:string; performanceAssessment?:PerformanceAssessment }
export interface RunIssue { id:string; moduleId:string; source:string; severity:'HIGH'|'MEDIUM'|'LOW'; evidenceConfidence:string; reviewStatus:string; taskStatus:string; title:string; description:string; evidence:string; createdAt:string }
export interface TestRun {
  id:string; projectId:string; targetId:string; suiteVersionId:string; status:string; cancelRequested:boolean
  configSnapshot:string; toolSnapshot:string; errorMessage?:string; createdAt:string; startedAt?:string; finishedAt?:string
  modules:RunModule[]; issues:RunIssue[]
}

export interface Version {
  id: string
  projectId: string
  baseVersionId?: string
  versionNo: number
  sourceType: string
  entryPage: string
  createdAt: string
}

export type ScanStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'CANCELLED'
export interface Scan {
  id: string
  projectId: string
  versionId: string
  status: ScanStatus
  stage: string
  progress: number
  foundCount: number
  highCount: number
  mediumCount: number
  lowCount: number
  reviewCount: number
  riskScore?: number
  errorMessage?: string
  createdAt: string
}

export interface Finding {
  id: string
  scanTaskId: string
  ruleId: string
  source: 'RULE' | 'HEURISTIC' | string
  severity: 'HIGH' | 'MEDIUM' | 'LOW'
  status: string
  title: string
  description: string
  pagePath: string
  selector: string
  fixMode: 'TEMPLATE_PATCH' | 'SUGGESTION' | 'NONE'
  evidenceFingerprint?: string
}

export interface FindingReview {
  id: string
  findingId: string
  action: 'CONFIRM_RISK' | 'DISMISS_FALSE_POSITIVE' | 'NEEDS_FOLLOW_UP'
  comment?: string
  reused: boolean
  sourcePolicyId?: string
  createdAt: string
}

export interface TraceStep {
  index?: number
  selector: string
  tag?: string
  role?: string
  accessibleName?: string
  name?: string
  tabindex?: string | null
  focusIndicator?: string
  domIndex?: number
  visualIndex?: number
  order?: number
  boundingBox?: { x:number; y:number; width:number; height:number }
}

export interface AnalysisTrace {
  id: string
  scanTaskId: string
  pagePath: string
  traceType: 'KEYBOARD_FOCUS' | 'READING_ORDER'
  status: string
  payload: Record<string, unknown>
  artifactId?: string
  createdAt: string
}

export interface Evidence {
  id: string
  findingId: string
  kind: string
  summary: string
  payload: string
  artifactId?: string
}

export interface AiStatus {
  available: boolean
  provider: string
  model?: string
  unavailableReason?: string
}

export interface Patch {
  id: string
  findingId: string
  baseVersionId: string
  newVersionId?: string
  targetFile: string
  diffText: string
  patchType: 'TEMPLATE' | 'AI_CANDIDATE'
  candidateText?: string
  decorative: boolean
  sourceEvidenceId?: string
  sourceProvider?: string
  sourceModel?: string
  reviewedAt?: string
  status: string
  verification: string
}
