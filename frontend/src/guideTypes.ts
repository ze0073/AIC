import type {Deployment, RuntimeConfig, Site} from './Websites'
import type {Workspace, Repair} from './IssueRepair'
import type {TestRun} from './types'
import type {RunReportSnapshot} from './reportSnapshot'

export type GuideGoal='DEPLOY'|'CHECK'|'REPAIR'|'REPORT'|'HELP'
export type GuideContext=Partial<Record<'projectId'|'siteId'|'versionId'|'deploymentId'|'targetId'|'suiteVersionId'|'baselineTaskId'|'taskId'|'runId'|'issueId'|'repairId'|'snapshotId',string>>
export type GuideDraft={name?:string;purpose?:string;path?:string;language?:string;candidateText?:string;decorative?:boolean;goal?:string;config?:RuntimeConfig;testPolicy?:{write:boolean;active:boolean;load:boolean};suiteVersionId?:string;repairMode?:string}
export type GuideStep={key:string;title:string;description:string;path?:string;anchor?:string}
export type GuideAction={id:string;sessionId:string;actionType:string;status:string;preview:Record<string,unknown>;actionHash:string;receipt:GuideContext&{operation?:string;contentHash?:string};errorMessage?:string;expiresAt:string}
export type GuidePreferences={welcomeSeen:boolean;characterVisible:boolean;voiceEnabled:boolean;qwenAvailable:boolean}
export type GuideSession={
  id:string;goal:GuideGoal;assistance:'GUIDED'|'DELEGATED';mode:'RULE'|'QWEN';aiConsent:boolean;status:'ACTIVE'|'COMPLETED';
  context:GuideContext;draft:GuideDraft;revision:number;updatedAt:string;step:string;suggestedGoals:GuideGoal[];
  messages:Array<{id:string;role:string;content:string;createdAt:string}>;actions:GuideAction[];
  resources:{site?:Site;deployment?:Deployment;repairWorkspace?:Workspace;repair?:Repair;run?:TestRun;taskRuns?:TestRun[];snapshot?:RunReportSnapshot;
    task?:{id:string;status:string;goal:string;currentRunId?:string;errorMessage?:string;steps:Array<{stepNo:number;status:string;runId?:string}>}}
}
