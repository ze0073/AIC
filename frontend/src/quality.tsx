import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Checkbox,
  Col,
  Descriptions,
  Divider,
  Empty,
  Flex,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  Progress,
  Row,
  Segmented,
  Select,
  Space,
  Spin,
  Statistic,
  Steps,
  Table,
  Tabs,
  Tag,
  Typography,
} from "antd";
import {
  ArrowLeftOutlined,
  CheckCircleFilled,
  CompassOutlined,
  GlobalOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  StopOutlined,
  ThunderboltOutlined,
} from "@ant-design/icons";
import { idempotencyKey, request } from "./api";
import { SuggestedValues } from "./SuggestedValues";
import { isLocalDemoTarget, suiteAssistantPlan, type SuiteAssistantKind } from "./suiteAssistant";
import { createRunReportSnapshot, latestOrCreateRunReportSnapshot, listRunReportSnapshots } from "./reportSnapshot";
import type { OnlineTarget, PerformanceAssessment, Project, TestRun, TestSuite, TestSuiteVersion } from "./types";
import { AnnotatedEvidence, FrontendMetricChart, PerformanceTrendChart, SecurityResultChart } from "./testEvidence";
import { IssueExplanationModal } from "./IssueExplanation";

type SuiteFormValues = { name:string;description?:string;pages?:string;apiCases?:Record<string,unknown>[];flowSteps?:Record<string,unknown>[];performancePath?:string;vus?:string;stageDurationSeconds?:number;p95Ms?:number;errorRatePercent?:number;ratePerSecond?:number };
function suiteDefinition(v:SuiteFormValues) {
  return {pages:(v.pages||"").split("\n").map(x=>x.trim()).filter(Boolean),flowSteps:v.flowSteps||[],apiCases:(v.apiCases||[]).map(item=>({
    ...item,activeParameters:String(item.activeParameters||"").split(",").map(x=>x.trim()).filter(Boolean),
    expectedStatuses:String(item.expectedStatuses||"").split(",").map(Number).filter(Number.isFinite),
    protectedFields:String(item.protectedFields||"").split(",").map(x=>x.trim()).filter(Boolean),
  })),performance:{path:v.performancePath||"",vus:(v.vus||"1").split(",").map(Number).filter(Number.isFinite),
    stageDurationSeconds:v.stageDurationSeconds??20,p95Ms:v.p95Ms??1000,errorRate:(v.errorRatePercent??1)/100,ratePerSecond:v.ratePerSecond??2}};
}

const moduleName: Record<string, string> = {
  FRONTEND: "前端质量",
  SECURITY: "后端安全",
  PERFORMANCE: "性能负载",
};
const friendlyModuleName: Record<string, string> = {
  FRONTEND: "页面体验",
  SECURITY: "接口与权限",
  PERFORMANCE: "承载能力",
};
const statusName: Record<string, string> = {
  AWAITING_START: "等待确认",
  PENDING: "等待执行",
  RUNNING: "执行中",
  COMPLETED: "已完成",
  PARTIAL: "部分完成",
  FAILED: "失败",
  SKIPPED: "已跳过",
  INCONCLUSIVE: "无法判断",
  CANCELLED: "已取消",
};
const statusColor: Record<string, string> = {
  RUNNING: "processing",
  COMPLETED: "success",
  PARTIAL: "warning",
  FAILED: "error",
  CANCELLED: "default",
  SKIPPED: "default",
};
const optionalReviewName: Record<string, string> = {
  PENDING_REVIEW: "未记录（可选）",
  CONFIRMED: "已记录风险",
  DISMISSED: "已驳回候选",
};

function Head({
  title,
  subtitle,
  back,
}: {
  title: string;
  subtitle?: string;
  back: string;
}) {
  return (
    <div className="page-head">
      <div>
        <Link to={back}>
          <ArrowLeftOutlined /> 返回
        </Link>
        <Typography.Title level={2}>{title}</Typography.Title>
        {subtitle && (
          <Typography.Paragraph type="secondary">
            {subtitle}
          </Typography.Paragraph>
        )}
      </div>
    </div>
  );
}

export function QualityCenterPage() {
  const { projectId = "" } = useParams(),
    nav = useNavigate(),
    cache = useQueryClient(),
    { message, modal } = AntApp.useApp();
  const [searchParams,setSearchParams]=useSearchParams();
  const runStatusFilter=searchParams.get("runStatus")||"ALL";
  const runModuleFilter=searchParams.get("runModule")||"ALL";
  const [targetOpen, setTargetOpen] = useState(false),
    [suiteOpen, setSuiteOpen] = useState(false),
    [runOpen, setRunOpen] = useState(false),
    [credentialOpen, setCredentialOpen] = useState(false);
  const [experienceMode,setExperienceMode]=useState<"SIMPLE"|"EXPERT">("SIMPLE");
  const [runPreset,setRunPreset]=useState<"QUICK"|"FULL"|"PAGE"|"CUSTOM">("QUICK");
  const [professionalOpen,setProfessionalOpen]=useState(false);
  const [suiteAdvancedOpen,setSuiteAdvancedOpen]=useState(false);
  const [editingSuite,setEditingSuite]=useState<TestSuite>();
  const [assistantKind,setAssistantKind]=useState<SuiteAssistantKind>("ENTRY");
  const [targetForm] = Form.useForm(),
    [registerTargetForm] = Form.useForm(),
    [suiteForm] = Form.useForm(),
    [runForm] = Form.useForm(),
    [credentialForm] = Form.useForm();
  const [scope, setScope] = useState<{
    baseUrl: string;
    origin: string;
    addresses: string[];
    pathPrefixes: string[];
  }>();
  useEffect(()=>{
    if(searchParams.get("suiteAssistant")!=="1")return;
    setEditingSuite(undefined);
    setAssistantKind("ENTRY");
    setSuiteAdvancedOpen(experienceMode==="EXPERT");
    suiteForm.resetFields();
    setSuiteOpen(true);
    const next=new URLSearchParams(searchParams);
    next.delete("suiteAssistant");
    setSearchParams(next,{replace:true});
  },[searchParams,setSearchParams,suiteForm,experienceMode]);
  const project = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => request<Project>(`/projects/${projectId}`),
  });
  const targets = useQuery({
    queryKey: ["targets", projectId],
    queryFn: () => request<OnlineTarget[]>(`/projects/${projectId}/targets`),
  });
  const suites = useQuery({
    queryKey: ["test-suites", projectId],
    queryFn: () => request<TestSuite[]>(`/projects/${projectId}/test-suites`),
  });
  const hasDemoTarget=!!targets.data?.some(target=>isLocalDemoTarget(target.baseUrl));
  const assistantPlan=suiteAssistantPlan(assistantKind);
  useEffect(()=>setProfessionalOpen(experienceMode==="EXPERT"),[experienceMode]);
  const openRunWizard=()=>{
    const targetId=targets.data?.[0]?.id;
    const suiteVersionId=suites.data?.[0]?.currentVersionId;
    setRunPreset("QUICK");
    runForm.setFieldsValue({targetId,suiteVersionId,modules:["FRONTEND","SECURITY"],activeScan:false});
    setRunOpen(true);
  };
  const applyRunPreset=(preset:"QUICK"|"FULL"|"PAGE"|"CUSTOM")=>{
    setRunPreset(preset);
    const modules=preset==="QUICK"?["FRONTEND","SECURITY"]:preset==="FULL"?["FRONTEND","SECURITY","PERFORMANCE"]:preset==="PAGE"?["FRONTEND"]:runForm.getFieldValue("modules")||["FRONTEND","SECURITY"];
    runForm.setFieldValue("modules",modules);
  };
  function openSuiteAssistant(){
    setEditingSuite(undefined);
    setAssistantKind("ENTRY");
    setSuiteAdvancedOpen(experienceMode==="EXPERT");
    suiteForm.resetFields();
    setSuiteOpen(true);
  }
  function applyAssistantPlan(){
    if(assistantKind==="LOCAL_DEMO"&&!hasDemoTarget){
      message.warning("请先登记本机演示站，再使用演示专用接口");
      return;
    }
    const apply=()=>{
      const current=suiteForm.getFieldsValue();
      suiteForm.setFieldsValue({...assistantPlan,
        name:current.name?.trim()?current.name:assistantPlan.name,
        description:current.description?.trim()?current.description:assistantPlan.description});
      message.success("参数已填入；核对后保存套件，再确认智能体计划");
    };
    if(suiteForm.isFieldsTouched(["pages","performancePath","vus","stageDurationSeconds",
      "ratePerSecond","p95Ms","errorRatePercent","apiCases","flowSteps"])){
      modal.confirm({title:"替换当前未保存的测试参数？",
        content:"将替换页面、接口用例、交互步骤和负载配置；保留你填写的名称与说明。已保存的套件和历史结果不变。",
        okText:"替换参数",cancelText:"保留当前填写",onOk:apply});
    }else apply();
  }
  const credentials = useQuery({
    queryKey: ["credentials", projectId],
    queryFn: () =>
      request<
        {
          id: string;
          name: string;
          targetId: string;
          principalType: string;
          authType: string;
        }[]
      >(`/projects/${projectId}/credentials`),
  });
  const runs = useQuery({
    queryKey: ["test-runs", projectId],
    queryFn: () => request<TestRun[]>(`/projects/${projectId}/test-runs`),
    refetchInterval: (q) =>
      q.state.data?.some((r) => ["PENDING", "RUNNING"].includes(r.status))
        ? 2000
        : false,
  });
  const agentTasks=useQuery({queryKey:["agent-tasks",projectId],queryFn:()=>
    request<Array<{id:string;goal:string;status:string}>>(`/projects/${projectId}/agent-tasks`),retry:false});
  const visibleRuns=useMemo(()=>(runs.data||[]).filter(run=>(runStatusFilter==="ALL"||run.status===runStatusFilter)
    &&(runModuleFilter==="ALL"||run.modules.some(module=>module.moduleType===runModuleFilter))),
  [runs.data,runStatusFilter,runModuleFilter]);
  function setRunFilter(name:"runStatus"|"runModule",value:string){
    const next=new URLSearchParams(searchParams);
    if(value==="ALL")next.delete(name);else next.set(name,value);
    setSearchParams(next,{replace:true});
  }
  const inspect = useMutation({
    mutationFn: (baseUrl: string) =>
      request<typeof scope>(
        `/projects/${projectId}/target-scope?baseUrl=${encodeURIComponent(baseUrl)}`,
      ),
    onSuccess: (data) => {registerTargetForm.resetFields();setScope(data)},
    onError: (e) => message.error((e as Error).message),
  });
  const createTarget = useMutation({
    mutationFn: (v: {
      name: string;
      allowActiveScan?: boolean;
      allowWriteTests?: boolean;
      cleanupInstructions?: string;
    }) =>
      request<OnlineTarget>(`/projects/${projectId}/targets`, {
        method: "POST",
        body: JSON.stringify({
          ...v,
          baseUrl: scope!.baseUrl,
          allowedAddresses: scope!.addresses,
          pathPrefixes: scope!.pathPrefixes,
        }),
      }),
    onSuccess: () => {
      message.success("在线目标已登记");
      setTargetOpen(false);
      setScope(undefined);
      targetForm.resetFields();
      registerTargetForm.resetFields();
      cache.invalidateQueries({ queryKey: ["targets", projectId] });
    },
    onError: (e) => message.error((e as Error).message),
  });
  const createCredential = useMutation({
    mutationFn: (v: {
      targetId: string;
      name: string;
      principalType: string;
      authType: string;
      secret: string;
    }) =>
      request(`/projects/${projectId}/credentials`, {
        method: "POST",
        body: JSON.stringify(v),
      }),
    onSuccess: () => {
      message.success("测试凭据已安全保存");
      setCredentialOpen(false);
      credentialForm.resetFields();
      cache.invalidateQueries({ queryKey: ["credentials", projectId] });
    },
    onError: (e) => message.error((e as Error).message),
  });
  const createSuite = useMutation({
    mutationFn: (v: SuiteFormValues):Promise<TestSuite|TestSuiteVersion> => editingSuite
      ? request<TestSuiteVersion>(`/test-suites/${editingSuite.id}/versions`,{method:"POST",headers:{"Idempotency-Key":idempotencyKey()},body:JSON.stringify(suiteDefinition(v))})
      : request<TestSuite>(`/projects/${projectId}/test-suites`,{method:"POST",body:JSON.stringify({name:v.name,description:v.description,definition:suiteDefinition(v)})}),
    onSuccess: () => {
      message.success(editingSuite?"新套件版本已发布，旧运行仍使用原版本":"测试套件已创建");
      setSuiteOpen(false);
      setEditingSuite(undefined);
      setSuiteAdvancedOpen(false);
      suiteForm.resetFields();
      cache.invalidateQueries({ queryKey: ["test-suites", projectId] });
    },
    onError: (e) => message.error((e as Error).message),
  });
  const createRun = useMutation({
    mutationFn: (v: {
      targetId: string;
      suiteVersionId: string;
      modules: string[];
      activeScan?: boolean;
    }) =>
      request<TestRun>("/test-runs", {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey() },
        body: JSON.stringify({ ...v, projectId }),
      }),
    onSuccess: (r) => {
      setRunOpen(false);
      message.success("检查已开始，正在打开进度页");
      nav(`/test-runs/${r.id}`);
    },
    onError: (e) => message.error((e as Error).message),
  });
  const activeTargetId = Form.useWatch("targetId", runForm);
  const activeTarget = targets.data?.find((t) => t.id === activeTargetId);
  if (project.isLoading) return <Spin />;
  return (
    <>
      <Head
        title={`${project.data?.name || "项目"} · 网站体检`}
        subtitle="按引导完成网站体检；需要时再展开专业参数。"
        back="/"
      />
      <div className="quality-modebar">
        <div><CompassOutlined/><span><b>界面模式</b><small>{experienceMode==="SIMPLE"?"只展示完成检查所需的关键步骤":"显示账号、版本和精细参数"}</small></span></div>
        <Segmented aria-label="界面模式" value={experienceMode} onChange={value=>setExperienceMode(value as "SIMPLE"|"EXPERT")} options={[{label:"简洁模式",value:"SIMPLE"},{label:"专业模式",value:"EXPERT"}]}/>
      </div>
      <section data-guide="quality-config" className="quality-guided-start">
        <div className="quality-guide-copy">
          <span className="section-kicker">GUIDED CHECK / 三步完成</span>
          <Typography.Title level={2}>{!targets.data?.length?"先添加要检查的网站":!suites.data?.length?"再选择一套检查方案":runs.data?.length?"可以开始新一轮检查":"已经准备好，可以开始检查"}</Typography.Title>
          <Typography.Paragraph>不需要理解测试术语。推荐设置适合首次体检，每一步都可以回看和修改。</Typography.Paragraph>
          <div className="quality-guide-assistant">
            <span className="assistant-glyph"><ThunderboltOutlined/></span>
            <span><b>不知道该选什么？</b><small>用一句话告诉项目助手你的目标，它会先生成计划，不会直接运行。</small></span>
            <Button type="primary" onClick={() => nav(`/projects/${projectId}/assistant`)}>让助手帮我</Button>
          </div>
        </div>
        <div className="quality-guide-steps">
          <article className={targets.data?.length?"complete":"active"}>
            <span className="guide-step-icon">{targets.data?.length?<CheckCircleFilled/>:<GlobalOutlined/>}</span>
            <div><small>第 1 步</small><h3>添加网站</h3><p>{targets.data?.length?`已添加 ${targets.data.length} 个网站`:"填写一个已获授权的网址，系统会自动识别范围。"}</p></div>
            <Button icon={<PlusOutlined/>} onClick={()=>setTargetOpen(true)}>{targets.data?.length?"再添加一个":"登记在线目标"}</Button>
          </article>
          <article className={suites.data?.length?"complete":targets.data?.length?"active":""}>
            <span className="guide-step-icon">{suites.data?.length?<CheckCircleFilled/>:<SafetyCertificateOutlined/>}</span>
            <div><small>第 2 步</small><h3>选择方案</h3><p>{suites.data?.length?`已有 ${suites.data.length} 套方案`:"推荐方案已设置安全的首次检查强度。"}</p></div>
            <Flex gap={6} wrap><Button aria-label="创建测试套件" onClick={openSuiteAssistant}>创建检查方案</Button><Button aria-label="帮我配置测试参数" type="link" icon={<ThunderboltOutlined/>} onClick={openSuiteAssistant}>使用推荐方案</Button></Flex>
          </article>
          <article className={runs.data?.length?"complete":targets.data?.length&&suites.data?.length?"active":""}>
            <span className="guide-step-icon">{runs.data?.length?<CheckCircleFilled/>:<PlayCircleOutlined/>}</span>
            <div><small>第 3 步</small><h3>开始检查</h3><p>{runs.data?.length?`已完成或发起 ${runs.data.length} 次检查`:"先进行日常体检，需要压力测试时再选全面检查。"}</p></div>
            <Button type="primary" icon={<PlayCircleOutlined/>} disabled={!targets.data?.length||!suites.data?.length} onClick={openRunWizard}>开始一次检查</Button>
          </article>
        </div>
      </section>
      <details className="quality-professional-tools" open={professionalOpen} onToggle={event=>setProfessionalOpen(event.currentTarget.open)}>
        <summary><span><SettingOutlined/> 专业工具与更多设置</span><small>测试账号、套件版本、精细执行和协作功能</small></summary>
        <div className="quality-professional-actions">
          <Button onClick={() => setCredentialOpen(true)} disabled={!targets.data?.length}>添加测试账号</Button>
          <Select placeholder="编辑并发布套件新版本" style={{minWidth:220}} value={undefined} options={suites.data?.map(s=>({value:s.id,label:`${s.name} · V${s.currentVersion}`}))} onChange={async id=>{
            const suite=suites.data?.find(s=>s.id===id);if(!suite)return;
            try{const version=await request<TestSuiteVersion>(`/test-suite-versions/${suite.currentVersionId}`);const def=version.definition as Record<string,unknown>;const perf=(def.performance||{}) as Record<string,unknown>;
              setEditingSuite(suite);setSuiteAdvancedOpen(true);suiteForm.setFieldsValue({name:suite.name,description:suite.description,pages:Array.isArray(def.pages)?def.pages.join("\n"):"",apiCases:Array.isArray(def.apiCases)?def.apiCases.map((item:Record<string,unknown>)=>({...item,activeParameters:Array.isArray(item.activeParameters)?item.activeParameters.join(","):"",expectedStatuses:Array.isArray(item.expectedStatuses)?item.expectedStatuses.join(","):"",protectedFields:Array.isArray(item.protectedFields)?item.protectedFields.join(","):""})):[],flowSteps:def.flowSteps||[],performancePath:perf.path||"",vus:Array.isArray(perf.vus)?perf.vus.join(","):"1,5,10",stageDurationSeconds:perf.stageDurationSeconds??20,p95Ms:perf.p95Ms??1000,errorRatePercent:Number(perf.errorRate??0.01)*100,ratePerSecond:perf.ratePerSecond??50});setSuiteOpen(true);
            }catch(e){message.error((e as Error).message)}
          }}/>
          <Button icon={<PlayCircleOutlined/>} disabled={!targets.data?.length||!suites.data?.length} onClick={()=>{runForm.setFieldsValue({targetId:targets.data?.[0]?.id,suiteVersionId:suites.data?.[0]?.currentVersionId,modules:["FRONTEND","SECURITY","PERFORMANCE"]});setRunPreset("CUSTOM");setRunOpen(true)}}>启动统一测试</Button>
          <Button onClick={() => nav(`/projects/${projectId}/agent`)}>使用表单创建智能计划</Button>
          <Button onClick={() => nav(`/projects/${projectId}/collaboration`)}>修复任务、案例与回归</Button>
        </div>
      </details>
      {!!agentTasks.data?.length&&<Card title="智能体任务" style={{marginBottom:16}}>
        <List size="small" dataSource={agentTasks.data.slice(0,5)} renderItem={task=><List.Item>
          <Link to={`/agent-tasks/${task.id}`}>{task.goal}</Link><Tag>{statusName[task.status]||task.status}</Tag>
        </List.Item>}/>
      </Card>}
      <Row gutter={[18, 18]}>
        <Col xs={24} lg={10}>
          <Card className="quality-resource-card" title="检查的网站" extra={<Typography.Text type="secondary">{targets.data?.length||0} 个</Typography.Text>}>
            {targets.data?.length ? (
              <List
                dataSource={targets.data}
                renderItem={(target) => (
                  <List.Item>
                    <div>
                      <b>{target.name}</b>
                      <div>
                        <Typography.Text type="secondary">
                          {target.baseUrl}
                        </Typography.Text>
                      </div>
                      <Space style={{ marginTop: 6 }}>
                        <Tag color="blue">
                          {experienceMode==="EXPERT"?target.allowedAddresses.join(", "):"范围已锁定"}
                        </Tag>
                        {target.allowActiveScan && (
                          <Tag color="orange">允许主动扫描</Tag>
                        )}
                      </Space>
                    </div>
                  </List.Item>
                )}
              />
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="还没有网站，请先完成上方第 1 步"
              />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={14}>
          <Card className="quality-resource-card" title={`运行记录 (${visibleRuns.length}/${runs.data?.length||0})`} extra={runs.data?.[0]&&<Button type="link" onClick={()=>nav(`/test-runs/${runs.data![0].id}`)}>查看最近结果</Button>}>
            <Typography.Paragraph type="secondary" className="quality-run-help">这里保留每次检查的结果。可以按状态或检查内容筛选。</Typography.Paragraph>
            <Flex gap={8} wrap style={{marginBottom:12}}>
              <Select aria-label="按状态筛选运行" value={runStatusFilter} style={{minWidth:142}}
                options={[{value:"ALL",label:"全部状态"},...Object.entries(statusName).map(([value,label])=>({value,label}))]}
                onChange={value=>setRunFilter("runStatus",value)}/>
              <Select aria-label="按模块筛选运行" value={runModuleFilter} style={{minWidth:148}}
                options={[{value:"ALL",label:"全部模块"},...Object.entries(moduleName).map(([value,label])=>({value,label}))]}
                onChange={value=>setRunFilter("runModule",value)}/>
            </Flex>
            <Table
              key={`${runStatusFilter}-${runModuleFilter}`}
              rowKey="id"
              size="small"
              pagination={{pageSize:8,showSizeChanger:false,hideOnSinglePage:true}}
              locale={{emptyText:"暂无符合筛选条件的运行"}}
              dataSource={visibleRuns}
              columns={[
                {
                  title: "创建时间",
                  dataIndex: "createdAt",
                  render: (v: string) => new Date(v).toLocaleString(),
                },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (v: string) => (
                    <Tag color={statusColor[v]}>{statusName[v] || v}</Tag>
                  ),
                },
                {
                  title: "模块",
                  render: (_: unknown, r: TestRun) =>
                    r.modules.map((m) => (
                      <Tag key={m.id}>{moduleName[m.moduleType]}</Tag>
                    )),
                },
                {
                  title: "",
                  render: (_: unknown, r: TestRun) => (
                    <Button
                      type="link"
                      onClick={() => nav(`/test-runs/${r.id}`)}
                    >
                      查看证据
                    </Button>
                  ),
                },
              ]}
            />
          </Card>
        </Col>
      </Row>
      <Modal
        title="添加要检查的网站"
        open={targetOpen}
        onCancel={() => {
          setTargetOpen(false);
          setScope(undefined);
          registerTargetForm.resetFields();
        }}
        footer={null}
      >
        <Steps size="small" current={scope?1:0} className="simple-modal-steps" items={[{title:"填写网址"},{title:"确认范围"}]}/>
        {!scope ? (
          <Form
            form={targetForm}
            layout="vertical"
            onFinish={(v) => inspect.mutate(v.baseUrl)}
          >
            <Form.Item
              name="baseUrl"
              label="网站入口地址"
              extra="请使用测试环境或你明确获准检查的网站。"
              rules={[{ required: true }, { type: "url" }]}
            >
              <Input placeholder="https://test.example.com/app/" />
            </Form.Item>
            <SuggestedValues items={[{label:"本机演示站",value:"http://127.0.0.1:8091/"}]}
              onSelect={baseUrl=>targetForm.setFieldValue("baseUrl",baseUrl)}
              hint="仅在本机演示站已启动时可用；真实网站请填写已获授权的测试环境地址。"/>
            <Alert
              type="info"
              showIcon
              title="下一步会自动识别网站范围"
              description="系统只会在确认后的域名和路径内检查，不会立即开始测试。"
            />
            <Button
              aria-label="检查目标范围"
              htmlType="submit"
              type="primary"
              block
              loading={inspect.isPending}
              style={{ marginTop: 16 }}
            >
              下一步：确认网站范围
            </Button>
          </Form>
        ) : (
          <Form form={registerTargetForm} layout="vertical" onFinish={(v) => createTarget.mutate(v)}>
            <Alert type="success" showIcon title="网站范围已识别，请确认后保存" description="这些限制会防止检查请求离开你登记的网站。" style={{marginBottom:14}}/>
            <Descriptions
              size="small"
              bordered
              column={1}
              items={[
                {
                  key: "origin",
                  label: "网站域名",
                  children: scope.origin,
                },
                {
                  key: "dns",
                  label: "解析到的地址",
                  children: scope.addresses.join(", "),
                },
                {
                  key: "path",
                  label: "允许检查的路径",
                  children: scope.pathPrefixes.join(", "),
                },
              ]}
            />
            <Divider />
            <Form.Item
              name="name"
              label="目标名称"
              rules={[{ required: true }]}
            >
              <Input placeholder="例如：校园报名测试站" />
            </Form.Item>
            <SuggestedValues items={[{label:"本机校园演示站",value:"本机校园演示站"},{label:"预发布测试站",value:"预发布测试站"}]}
              onSelect={name=>registerTargetForm.setFieldValue("name",name)}/>
            <details className="form-advanced-disclosure">
              <summary>高级权限设置 <span>默认关闭，首次使用可以跳过</span></summary>
              <Alert type="warning" showIcon title="这些选项会产生更强的测试请求，请仅在明确授权后开启。" style={{marginBottom:14}}/>
              <Form.Item name="allowActiveScan" valuePropName="checked">
                <Checkbox>允许 SQL 注入与反射型 XSS 主动扫描</Checkbox>
              </Form.Item>
              <Form.Item name="allowWriteTests" valuePropName="checked">
                <Checkbox>允许写接口和上传测试</Checkbox>
              </Form.Item>
              <Form.Item name="cleanupInstructions" label="测试数据清理说明">
                <Input.TextArea rows={2} placeholder="例如：测试结束后删除以 qa- 开头的数据"/>
              </Form.Item>
            </details>
            <Flex gap={8}>
              <Button onClick={() => {setScope(undefined);registerTargetForm.resetFields()}}>重新检查</Button>
              <Button
                htmlType="submit"
                type="primary"
                loading={createTarget.isPending}
              >
                保存网站
              </Button>
            </Flex>
          </Form>
        )}
      </Modal>
      <Modal
        title="添加测试账号"
        open={credentialOpen}
        onCancel={() => setCredentialOpen(false)}
        onOk={() => credentialForm.submit()}
        confirmLoading={createCredential.isPending}
      >
        <Form
          form={credentialForm}
          layout="vertical"
          initialValues={{ authType: "BEARER", principalType: "USER_A" }}
          onFinish={(v) => createCredential.mutate(v)}
        >
          <Form.Item name="targetId" label="目标" rules={[{ required: true }]}>
            <Select
              options={targets.data?.map((t) => ({
                value: t.id,
                label: t.name,
              }))}
            />
          </Form.Item>
          <Form.Item name="name" label="账号备注" rules={[{ required: true }]}>
            <Input placeholder="普通用户 A" />
          </Form.Item>
          <Form.Item
            name="principalType"
            label="身份"
            rules={[{ required: true }]}
          >
            <Select
              options={["USER_A", "USER_B", "ADMIN"].map((value) => ({
                value,
                label: value,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="authType"
            label="凭据类型"
            rules={[{ required: true }]}
          >
            <Select
              options={[
                { value: "BEARER", label: "Bearer Token" },
                { value: "COOKIE", label: "Cookie" },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="secret"
            label="Token 或 Cookie"
            rules={[{ required: true }]}
          >
            <Input.Password autoComplete="off" />
          </Form.Item>
          <Alert
            type="info"
            showIcon
            title="凭据加密保存，只会发送到绑定目标的 Origin。"
          />
        </Form>
      </Modal>
      <Modal
        title={editingSuite?`更新 ${editingSuite.name} 的专业参数`:"创建检查方案"}
        open={suiteOpen}
        onCancel={() => {setSuiteOpen(false);setEditingSuite(undefined);setSuiteAdvancedOpen(false);suiteForm.resetFields()}}
        onOk={() => suiteForm.submit()}
        okText={editingSuite?"发布新版本":"保存检查方案"}
        cancelText="暂不保存"
        confirmLoading={createSuite.isPending}
        width={820}
      >
        {!editingSuite&&<Card size="small" className="suite-recommendation" title={<Space><span className="recommendation-badge"><ThunderboltOutlined/></span><span>推荐：首次安全体检</span></Space>} style={{marginBottom:16}}>
          <Tag color="green">规则模板 · 未调用模型</Tag>
          <Typography.Paragraph style={{marginTop:8}}>适合第一次使用：检查入口页，以很低的请求强度运行。点击填入后可以直接保存，也可以展开高级参数修改。</Typography.Paragraph>
          <Select aria-label="参数推荐方案" value={assistantKind} style={{width:"100%",marginBottom:12}}
            onChange={setAssistantKind} options={[
              {value:"ENTRY",label:"首次安全体检（推荐）"},
              {value:"LOCAL_DEMO",label:"本机演示（健康接口 + 慢接口）",disabled:!hasDemoTarget},
            ]}/>
          <div className="suite-recommendation-glance">
            <span><CheckCircleFilled/><b>入口页</b><small>从登记的网站首页开始</small></span>
            <span><CheckCircleFilled/><b>轻量请求</b><small>适合第一次了解现状</small></span>
            <span><CheckCircleFilled/><b>只读范围</b><small>不点击、不填写、不上传</small></span>
          </div>
          <details className="suite-template-details">
            <summary>查看技术参数</summary>
            <Descriptions size="small" column={1} items={[
              {key:"pages",label:"页面",children:"所选在线目标的入口页，不猜测其他路径"},
              {key:"api",label:"只读接口",children:assistantKind==="LOCAL_DEMO"?"GET /api/health，预期 HTTP 200":"不猜测接口；未配置权限用例，不能据此认定权限安全"},
              {key:"load",label:"负载",children:`${assistantPlan.vus} 个虚拟用户（依次执行），每阶段 20 秒，速率上限 2 次/秒；${assistantPlan.performancePath||"使用入口页"}`},
              {key:"threshold",label:"起始参考阈值",children:"P95 ≤ 1000 毫秒、错误率 < 1%；未按实测基线校准，不是通过保证"},
              {key:"exclusions",label:"不包含",children:"登录凭据、写入/上传、主动扫描、点击或填写操作"},
            ]}/>
          </details>
          {assistantKind==="LOCAL_DEMO"&&<Alert type="warning" showIcon title="演示方案仅用于 http://127.0.0.1:8091/；启动时请选择该在线目标。" style={{marginTop:12}}/>}
          <Button htmlType="button" type="primary" onClick={applyAssistantPlan} style={{marginTop:12}}>填入推荐参数</Button>
          <Typography.Paragraph type="secondary" style={{marginTop:12,marginBottom:0}}>推荐值不会自动保存或运行。下一步只需填写方案名称并保存。</Typography.Paragraph>
        </Card>}
        <Form
          form={suiteForm}
          layout="vertical"
          onFinish={(v) => createSuite.mutate(v)}
        >
          <Form.Item name="name" label="方案名称" extra="用于以后快速选择，例如“日常网站体检”。" rules={[{ required: true }]}>
            <Input placeholder="基础质量验收" disabled={!!editingSuite}/>
          </Form.Item>
          {!editingSuite&&<SuggestedValues items={[{label:"基础质量验收",value:"基础质量验收"},{label:"每日回归检查",value:"每日回归检查"}]}
            onSelect={name=>suiteForm.setFieldValue("name",name)}/>}
          <Form.Item name="description" label="补充说明（可选）">
            <Input placeholder="例如：每次发布前运行一次"/>
          </Form.Item>
          <SuggestedValues items={[{label:"仅检查入口页",value:""}]}
            onSelect={pages=>suiteForm.setFieldValue("pages",pages)}
            hint="首次使用保持此设置即可；系统会从你登记的网站入口开始。"/>
          <div className="suite-simple-summary">
            <span><CheckCircleFilled/> 入口页体验检查</span>
            <span><CheckCircleFilled/> 低强度安全检查</span>
            <span><CheckCircleFilled/> 保留证据与通俗总结</span>
          </div>
          <details className="suite-advanced-settings" open={suiteAdvancedOpen} onToggle={event=>setSuiteAdvancedOpen(event.currentTarget.open)}>
            <summary><span><SettingOutlined/> 高级参数</span><small>页面范围、性能阈值、接口权限和交互步骤</small></summary>
            <Alert type="info" showIcon title="不确定时请保留推荐值" description="这些参数适合熟悉接口、并发与性能阈值的人员调整。" style={{marginBottom:16}}/>
          <Form.Item name="pages" label="页面地址（每行一个，最多 10 个）">
            <Input.TextArea rows={3} placeholder="留空时使用目标入口页" />
          </Form.Item>
          <Form.Item name="performancePath" label="负载测试接口路径">
            <Input placeholder="例如 /api/slow；留空时测试入口页" />
          </Form.Item>
          <SuggestedValues items={[{label:"使用入口页",value:""},...(hasDemoTarget?[{label:"本机演示慢接口",value:"/api/slow"}]:[])]}
            onSelect={performancePath=>suiteForm.setFieldValue("performancePath",performancePath)}
            hint="负载测试会产生真实请求；请先确认目标及速率上限。"/>
          <Row gutter={12}>
            <Col span={12}><Form.Item name="vus" label="负载阶梯 VU（逗号分隔，最多 3 阶段）" initialValue="1" rules={[{required:true},{pattern:/^\s*\d+\s*(,\s*\d+\s*){0,2}$/,message:"填写 1–3 个整数"}]}><Input placeholder="1,5,10"/></Form.Item></Col>
            <Col span={12}><Form.Item name="stageDurationSeconds" label="每阶段时长（秒）" initialValue={20} rules={[{required:true}]}><InputNumber min={1} max={40} style={{width:"100%"}}/></Form.Item></Col>
            <Col span={8}><Form.Item name="ratePerSecond" label="速率上限（次/秒）" initialValue={2} rules={[{required:true}]}><InputNumber min={1} max={50} style={{width:"100%"}}/></Form.Item></Col>
            <Col span={8}><Form.Item name="p95Ms" label="P95 阈值（毫秒）" initialValue={1000} rules={[{required:true}]}><InputNumber min={50} max={60000} style={{width:"100%"}}/></Form.Item></Col>
            <Col span={8}><Form.Item name="errorRatePercent" label="错误率阈值（%）" initialValue={1} rules={[{required:true}]}><InputNumber min={0.01} max={50} step={0.1} style={{width:"100%"}}/></Form.Item></Col>
          </Row>
          <Divider>接口与权限用例</Divider>
          <Form.List name="apiCases">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field) => (
                  <Card
                    key={field.key}
                    size="small"
                    style={{ marginBottom: 12 }}
                    extra={
                      <Button
                        type="link"
                        danger
                        onClick={() => remove(field.name)}
                      >
                        删除
                      </Button>
                    }
                  >
                    <Row gutter={12}>
                      <Col span={12}>
                        <Form.Item
                          name={[field.name, "name"]}
                          label="用例名称"
                          rules={[{ required: true }]}
                        >
                          <Input />
                        </Form.Item>
                      </Col>
                      <Col span={12}>
                        <Form.Item
                          name={[field.name, "kind"]}
                          label="检查类型"
                          initialValue="ASSERTION"
                        >
                          <Select
                            options={[
                              { value: "ASSERTION", label: "接口断言" },
                              {
                                value: "UNAUTHENTICATED_ACCESS",
                                label: "未认证访问",
                              },
                              {
                                value: "ROLE_AUTHORIZATION",
                                label: "角色越权",
                              },
                              {
                                value: "CROSS_USER_ACCESS",
                                label: "跨用户访问",
                              },
                              { value: "UPLOAD_SECURITY", label: "上传安全" },
                            ]}
                          />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Row gutter={12}>
                      <Col span={6}>
                        <Form.Item
                          name={[field.name, "method"]}
                          label="方法"
                          initialValue="GET"
                        >
                          <Select
                            options={["GET", "POST", "PUT", "DELETE"].map(
                              (value) => ({ value, label: value }),
                            )}
                          />
                        </Form.Item>
                      </Col>
                      <Col span={18}>
                        <Form.Item
                          name={[field.name, "path"]}
                          label="接口路径（查询参数写在路径中）"
                          rules={[{ required: true }]}
                        >
                          <Input placeholder="/api/search?keyword=test" />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Row gutter={12}>
                      <Col span={12}>
                        <Form.Item
                          name={[field.name, "credentialId"]}
                          label="测试账号"
                        >
                          <Select
                            allowClear
                            options={credentials.data?.map((c) => ({
                              value: c.id,
                              label: `${c.name} · ${c.principalType}`,
                            }))}
                          />
                        </Form.Item>
                      </Col>
                      <Col span={12}>
                        <Form.Item
                          name={[field.name, "expectedStatuses"]}
                          label="预期状态码（逗号分隔）"
                        >
                          <Input placeholder="403" />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Row gutter={12}>
                      <Col span={12}>
                        <Form.Item
                          name={[field.name, "protectedFields"]}
                          label="受保护 JSON 字段（逗号分隔）"
                        >
                          <Input placeholder="internalNote,owner" />
                        </Form.Item>
                      </Col>
                      <Col span={12}>
                        <Form.Item
                          name={[field.name, "activeParameters"]}
                          label="主动扫描参数名（逗号分隔）"
                        >
                          <Input placeholder="keyword" />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Row gutter={12}>
                      <Col span={12}>
                        <Form.Item
                          name={[field.name, "expectedOwner"]}
                          label="跨用户资源实际所有者"
                        >
                          <Input placeholder="user-b" />
                        </Form.Item>
                      </Col>
                      <Col span={12}>
                        <Form.Item
                          name={[field.name, "cleanupPath"]}
                          label="上传清理路径"
                        >
                          <Input placeholder="/api/uploads/{id}" />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Form.Item
                      name={[field.name, "activeScan"]}
                      valuePropName="checked"
                    >
                      <Checkbox>
                        明确授权对此 GET 接口的指定参数运行 ZAP 主动扫描
                      </Checkbox>
                    </Form.Item>
                  </Card>
                ))}
                <Button
                  block
                  onClick={() => add()}
                  disabled={fields.length >= 10}
                >
                  添加接口用例（最多 10 个）
                </Button>
              </>
            )}
          </Form.List>
          <Divider>页面交互步骤</Divider>
          <Form.List name="flowSteps">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field) => (
                  <Row gutter={8} key={field.key}>
                    <Col span={6}>
                      <Form.Item
                        name={[field.name, "action"]}
                        initialValue="ASSERT_VISIBLE"
                      >
                        <Select
                          options={[
                            "NAVIGATE",
                            "CLICK",
                            "FILL",
                            "ASSERT_VISIBLE",
                            "ASSERT_TEXT",
                          ].map((value) => ({ value, label: value }))}
                        />
                      </Form.Item>
                    </Col>
                    <Col span={7}>
                      <Form.Item name={[field.name, "selector"]}>
                        <Input placeholder="CSS 选择器" />
                      </Form.Item>
                    </Col>
                    <Col span={9}>
                      <Form.Item name={[field.name, "value"]}>
                        <Input placeholder="填写内容" />
                      </Form.Item>
                    </Col>
                    <Col span={2}>
                      <Button danger onClick={() => remove(field.name)}>
                        删
                      </Button>
                    </Col>
                  </Row>
                ))}
                <Button
                  block
                  onClick={() => add()}
                  disabled={fields.length >= 20}
                >
                  添加流程步骤（最多 20 步）
                </Button>
              </>
            )}
          </Form.List>
          </details>
          <Alert type="info" showIcon title="发布新版本不会改写旧运行；回归计划仍绑定原套件版本，需另行更新。" style={{marginTop:16}}/>
        </Form>
      </Modal>
      <Modal
        title="开始一次检查"
        open={runOpen}
        onCancel={() => setRunOpen(false)}
        onOk={() => runForm.submit()}
        confirmLoading={createRun.isPending}
        okText="确认并开始"
        cancelText="暂不开始"
        width={720}
        forceRender
      >
        <div className="run-wizard-intro">
          <span><PlayCircleOutlined/></span>
          <div><b>选择检查深度</b><p>日常体检不会施加性能负载；需要验证承载能力时再选择全面检查。</p></div>
        </div>
        <div className="run-preset-grid" role="group" aria-label="检查深度">
          <button type="button" className={runPreset==="QUICK"?"selected":""} aria-pressed={runPreset==="QUICK"} onClick={()=>applyRunPreset("QUICK")}>
            <span><b>日常体检</b><Tag color="green">推荐</Tag></span><small>检查页面体验和只读安全用例，不运行压力测试。</small>
          </button>
          <button type="button" className={runPreset==="FULL"?"selected":""} aria-pressed={runPreset==="FULL"} onClick={()=>applyRunPreset("FULL")}>
            <span><b>全面检查</b></span><small>在日常体检基础上增加受控性能负载。</small>
          </button>
          <button type="button" className={runPreset==="PAGE"?"selected":""} aria-pressed={runPreset==="PAGE"} onClick={()=>applyRunPreset("PAGE")}>
            <span><b>仅看页面</b></span><small>只检查页面加载、结构和可用性。</small>
          </button>
          {experienceMode==="EXPERT"&&<button type="button" className={runPreset==="CUSTOM"?"selected":""} aria-pressed={runPreset==="CUSTOM"} onClick={()=>applyRunPreset("CUSTOM")}>
            <span><b>自定义</b></span><small>自行选择检测模块和主动扫描选项。</small>
          </button>}
        </div>
        <Form
          form={runForm}
          layout="vertical"
          initialValues={{ modules: ["FRONTEND", "SECURITY"] }}
          onFinish={(v) => createRun.mutate(v)}
        >
          <Form.Item
            name="targetId"
            label="检查哪个网站"
            rules={[{ required: true }]}
          >
            <Select
              options={targets.data?.map((t) => ({
                value: t.id,
                label: t.name,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="suiteVersionId"
            label="使用哪套检查方案"
            rules={[{ required: true }]}
          >
            <Select
              options={suites.data?.map((s) => ({
                value: s.currentVersionId,
                label: `${s.name} · V${s.currentVersion}`,
              }))}
            />
          </Form.Item>
          {runPreset==="CUSTOM"?<Form.Item name="modules" label="检测模块" rules={[{ required: true }]}>
            <Checkbox.Group options={Object.entries(moduleName).map(([value, label]) => ({value,label}))}/>
          </Form.Item>:<Form.Item name="modules" hidden rules={[{required:true}]}><Checkbox.Group/></Form.Item>}
          <div className="run-scope-summary" aria-live="polite">
            <b>本次将按顺序执行</b>
            <Flex gap={8} wrap>{((runForm.getFieldValue("modules")||[]) as string[]).map(module=><Tag color="green" key={module}>{friendlyModuleName[module]||moduleName[module]}</Tag>)}</Flex>
            <small>每个模块完成后都会保存状态、图表和可追溯证据。</small>
          </div>
          <details className="form-advanced-disclosure">
            <summary>高级安全选项 <span>默认关闭</span></summary>
            <Form.Item name="activeScan" valuePropName="checked">
              <Checkbox disabled={!activeTarget?.allowActiveScan}>执行限定主动漏洞扫描（仅 SQL 注入与反射型 XSS）</Checkbox>
            </Form.Item>
            {!activeTarget?.allowActiveScan&&<Typography.Text type="secondary">当前网站没有授权主动扫描，因此无法开启。</Typography.Text>}
          </details>
          <Alert
            type="info"
            showIcon
            title="系统会逐项执行，避免不同检查互相干扰"
            description="点击开始前不会发送测试请求；开始后可以在任务页实时查看进度并随时取消。"
          />
        </Form>
      </Modal>
    </>
  );
}

export function TestRunPage() {
  const { runId = "" } = useParams(),
    { message } = AntApp.useApp();
  const [baselineRunId,setBaselineRunId]=useState<string>();
  const [reportSnapshotId,setReportSnapshotId]=useState<string|undefined>(()=>window.sessionStorage.getItem(`run-report-snapshot:${runId}`)||undefined);
  const selectReportSnapshot=(id:string)=>{
    setReportSnapshotId(id);
    window.sessionStorage.setItem(`run-report-snapshot:${runId}`,id);
  };
  const run = useQuery({
    queryKey: ["test-run", runId],
    queryFn: () => request<TestRun>(`/test-runs/${runId}`),
    refetchInterval: (q) =>
      q.state.data &&
      !["COMPLETED", "PARTIAL", "FAILED", "CANCELLED"].includes(
        q.state.data.status,
      )
        ? 1500
        : false,
  });
  const cancel = useMutation({
    mutationFn: () =>
      request<TestRun>(`/test-runs/${runId}/cancel`, { method: "POST" }),
    onSuccess: () => {
      message.info("已请求停止运行");
      run.refetch();
    },
    onError: (e) => message.error((e as Error).message),
  });
  const history=useQuery({queryKey:["test-runs",run.data?.projectId],queryFn:()=>request<TestRun[]>(`/projects/${run.data!.projectId}/test-runs`),enabled:!!run.data});
  const comparison=useQuery({queryKey:["run-comparison",runId,baselineRunId],queryFn:()=>request<RunComparison>(`/test-runs/${runId}/comparison?baselineRunId=${baselineRunId}`),enabled:!!baselineRunId});
  const reportSnapshots = useQuery({
    queryKey: ["test-run-report-snapshots", runId],
    queryFn: () => listRunReportSnapshots(runId),
    enabled: ["COMPLETED", "PARTIAL", "FAILED", "CANCELLED"].includes(run.data?.status || ""),
  });
  const createReport = useMutation({
    mutationFn: () => createRunReportSnapshot(runId),
    onSuccess: (snapshot) => {
      selectReportSnapshot(snapshot.id);
      message.success("已生成新的不可变报告快照");
      reportSnapshots.refetch();
    },
    onError: (e) => message.error((e as Error).message),
  });
  const exportReport = useMutation({
    mutationFn: async (format: "pdf" | "json") => {
      const snapshot = await latestOrCreateRunReportSnapshot(runId, reportSnapshotId);
      return { format, id: snapshot.id };
    },
    onSuccess: (value) => {
      selectReportSnapshot(value.id);
      reportSnapshots.refetch();
      window.location.href = `/api/test-run-report-snapshots/${value.id}/export?format=${value.format}`;
    },
    onError: (e) => message.error((e as Error).message),
  });
  if (!run.data) return <Spin />;
  const selectedReportSnapshot = reportSnapshots.data?.find((snapshot) => snapshot.id === reportSnapshotId && snapshot.schemaVersion === "0.6.1")
    ?? reportSnapshots.data?.find((snapshot) => snapshot.schemaVersion === "0.6.1");
  const value = run.data,
    done = value.modules.filter((m) =>
      ["COMPLETED", "FAILED", "PARTIAL", "SKIPPED", "CANCELLED"].includes(
        m.status,
      ),
    ).length;
  const failedAssessment=value.modules.find(m=>m.moduleType==="PERFORMANCE"&&m.performanceAssessment?.status==="FAILED")?.performanceAssessment;
  return (
    <>
      <Head
        title="统一测试运行"
        subtitle={`${new Date(value.createdAt).toLocaleString()} · ${failedAssessment?"执行已结束，性能未通过":statusName[value.status] || value.status}`}
        back={`/projects/${value.projectId}/quality`}
      />
      <Card className="run-overview">
        <Flex justify="space-between" align="center" gap={16} wrap>
          <div className="grow">
            <Progress
              percent={
                value.modules.length
                  ? Math.round((done / value.modules.length) * 100)
                  : 0
              }
              status={
                value.status === "FAILED" || failedAssessment
                  ? "exception"
                  : value.status === "COMPLETED"
                    ? "success"
                    : "active"
              }
            />
            <Typography.Text type="secondary">
              执行完成不等于性能通过；各模块结果独立保留，未执行项目不会显示为通过。
            </Typography.Text>
          </div>
          <Space>
            <Button
              disabled={
                !["COMPLETED", "PARTIAL", "FAILED", "CANCELLED"].includes(value.status) || createReport.isPending
              }
              loading={exportReport.isPending}
              onClick={() => exportReport.mutate("pdf")}
            >
              导出 PDF
            </Button>
            <Button
              disabled={
                !["COMPLETED", "PARTIAL", "FAILED", "CANCELLED"].includes(value.status) || createReport.isPending
              }
              loading={exportReport.isPending}
              onClick={() => exportReport.mutate("json")}
            >
              导出 JSON
            </Button>
            <Button
              disabled={!["COMPLETED", "PARTIAL", "FAILED", "CANCELLED"].includes(value.status) || exportReport.isPending}
              loading={createReport.isPending}
              onClick={() => createReport.mutate()}
            >
              生成新快照
            </Button>
            <Button
              danger
              icon={<StopOutlined />}
              disabled={[
                "COMPLETED",
                "PARTIAL",
                "FAILED",
                "CANCELLED",
              ].includes(value.status)}
              loading={cancel.isPending}
              onClick={() => cancel.mutate()}
            >
              停止
            </Button>
          </Space>
        </Flex>
        {failedAssessment&&<Alert style={{marginTop:14}} type="error" showIcon title="性能验收未通过" description={failedAssessment.reason}/>}
        {selectedReportSnapshot && (
          <Typography.Paragraph data-guide="run-report" type="secondary" style={{ marginTop: 12, marginBottom: 0, wordBreak: "break-all" }}>
            当前导出使用 V0.6.1 快照：{selectedReportSnapshot.contentHash}。
            历史快照保留当时的解释，不会被本次修正改写；如需在导出的报告中看到最新判定，请先生成新快照。
          </Typography.Paragraph>
        )}
      </Card>
      <Steps
        className="module-steps"
        responsive
        items={value.modules.map((m) => ({
          title: moduleName[m.moduleType],
          status:
            m.performanceAssessment?.status === "FAILED"
              ? "error"
              : m.status === "COMPLETED"
              ? "finish"
              : m.status === "RUNNING"
                ? "process"
                : m.status === "PENDING"
                  ? "wait"
                  : "error",
          description: m.performanceAssessment?.status === "FAILED" ? m.performanceAssessment.reason : m.summary || statusName[m.status],
        }))}
      />
      <Card title="修复前后对比" style={{marginBottom:18}}>
        <Select allowClear value={baselineRunId} onChange={setBaselineRunId} placeholder="选择基线运行" style={{width:320,maxWidth:"100%"}} options={history.data?.filter(item=>item.id!==runId).map(item=>({value:item.id,label:`${new Date(item.createdAt).toLocaleString()} · ${item.id.slice(0,8)}`}))}/>
        {comparison.isError&&<Alert type="error" title={(comparison.error as Error).message} style={{marginTop:12}}/>}
        {comparison.data&&<div style={{marginTop:14}}>
          {comparison.data.reasons.length>0&&<Alert type="warning" title={`整体不可比：${comparison.data.reasons.join("；")}`}/>}
          <Row gutter={[12,12]} style={{marginTop:12}}>{Object.entries(comparison.data.modules).map(([type,item])=><Col xs={24} lg={8} key={type}><Card size="small" title={moduleName[type]}>
            {!item.comparable?<Typography.Text type="secondary">不可比：{item.reasons?.join("；")}</Typography.Text>:<Descriptions size="small" column={1} items={Object.entries(item.delta||{}).filter(([,v])=>typeof v==="number").map(([key,delta])=>({key,label:metricLabel[key]||key,children:`${formatMetric(key,item.baseline?.[key])} → ${formatMetric(key,item.current?.[key])}（${Number(delta)>0?"+":""}${formatMetric(key,delta)}）`}))}/>}
            {item.comparable&&!!item.delta?.pages&&<div>{Object.entries(item.delta.pages as Record<string,Record<string,number>>).map(([page,fields])=><div key={page}><Typography.Text strong>{page}</Typography.Text><div>{Object.entries(fields).map(([key,delta])=><div key={key}>{metricLabel[key]||key}：{formatMetric(key,(item.baseline?.pages as Record<string,Record<string,number>>)?.[page]?.[key])} → {formatMetric(key,(item.current?.pages as Record<string,Record<string,number>>)?.[page]?.[key])}（{delta>0?"+":""}{formatMetric(key,delta)}）</div>)}</div></div>)}</div>}
          </Card></Col>)}</Row>
        </div>}
      </Card>
      <Row gutter={[18, 18]}>
        {value.modules.map((module) => (
          <Col xs={24} key={module.id}>
            <Card
              title={
                <Space>
                  <span>{moduleName[module.moduleType]}</span>
                  <Tag color={module.performanceAssessment?.status==="FAILED"?"red":module.performanceAssessment?.status==="INCONCLUSIVE"?"orange":statusColor[module.status]}>
                    {module.performanceAssessment?.status==="FAILED"?"执行结束 · 未通过":module.performanceAssessment?.status==="INCONCLUSIVE"?"结果不足":statusName[module.status] || module.status}
                  </Tag>
                </Space>
              }
            >
              <Typography.Paragraph>{module.summary}</Typography.Paragraph>
              {module.moduleType==="PERFORMANCE"&&module.performanceAssessment&&<Alert style={{marginBottom:12}} type={module.performanceAssessment.status==="FAILED"?"error":module.performanceAssessment.status==="INCONCLUSIVE"?"warning":"success"} title={`性能判定：${module.performanceAssessment.status==="PASSED"?"通过":module.performanceAssessment.status==="FAILED"?"未通过":"无法判定"}`} description={module.performanceAssessment.reason}/>}
              {module.resultPayload&&<ModuleResults type={module.moduleType} payload={parseObject(module.resultPayload)} performanceAssessment={module.performanceAssessment}/>}
              {module.resultPayload && (
                <details>
                  <summary>高级证据：自动标注 JSON 快照</summary>
                  <AnnotatedEvidence raw={module.resultPayload} performanceAssessment={module.performanceAssessment}/>
                </details>
              )}
            </Card>
          </Col>
        ))}
      </Row>
      <Card
        title={`问题与证据（${value.issues.length}）`}
        className="issues-card"
      >
        {value.issues.length ? (
          <List
            dataSource={value.issues}
            renderItem={(issue) => (
              <List.Item>
                <div className="issue-row">
                  <Space wrap>
                    <Tag
                      color={
                        issue.severity === "HIGH"
                          ? "red"
                          : issue.severity === "MEDIUM"
                            ? "orange"
                            : "blue"
                      }
                    >
                      {issue.severity}
                    </Tag>
                    <Tag>{issue.source}</Tag>
                    <Tag
                      color={
                        issue.evidenceConfidence === "ENGINE_CANDIDATE"
                          ? "gold"
                          : "default"
                      }
                    >
                      {issue.evidenceConfidence}
                    </Tag>
                  </Space>
                  <Typography.Title level={5}>{issue.title}</Typography.Title>
                  <Space wrap><Tag>可选审阅：{optionalReviewName[issue.reviewStatus] || issue.reviewStatus}</Tag><Tag>修复任务：{issue.taskStatus}</Tag></Space>
                  <Typography.Paragraph>
                    {issue.description}
                  </Typography.Paragraph>
                  <details>
                    <summary>查看带注释的脱敏证据</summary>
                    <AnnotatedEvidence raw={issue.evidence}/>
                  </details>
                  <Link to={`/issues/${issue.id}/repair`}><Button type="primary">开始修复</Button></Link>{' '}
                  <Link to={`/projects/${value.projectId}/collaboration?issueId=${issue.id}`}>
                    按需审阅此问题
                  </Link>
                </div>
              </List.Item>
            )}
          />
        ) : (
          <Empty description="没有保存的问题记录；性能失败或未完成仍可能存在，请以上方模块判定为准。" />
        )}
      </Card>
    </>
  );
}

type RunComparison={runId:string;baselineRunId:string;comparable:boolean;reasons:string[];modules:Record<string,{comparable:boolean;reasons?:string[];baseline?:Record<string,unknown>;current?:Record<string,unknown>;delta?:Record<string,unknown>}>};
const metricLabel:Record<string,string>={ttfbMs:"TTFB",navigationMs:"导航耗时",lcpMs:"LCP",cls:"CLS",requestCount:"请求数",declaredResourceBytes:"资源字节",p50Ms:"P50",p95Ms:"P95",p99Ms:"P99",throughputPerSecond:"吞吐 / 秒",errorRate:"错误率",issueCount:"问题或候选数",humanConfirmedCount:"已记录风险数"};
function parseObject(raw?:string):Record<string,unknown>{try{return JSON.parse(raw||"{}") as Record<string,unknown>}catch{return {}}}
function formatMetric(key:string,value:unknown){if(typeof value!=="number")return "—";return key==="errorRate"?`${(value*100).toFixed(2)}%`:value.toFixed(key==="cls"?3:1)}
function ModuleResults({type,payload,performanceAssessment}:{type:string;payload:Record<string,unknown>;performanceAssessment?:PerformanceAssessment}){
  if(type==="FRONTEND"){
    const measurements=Array.isArray(payload.measurements)?payload.measurements as Record<string,unknown>[]:[];
    return <><FrontendMetricChart measurements={measurements}/><Table size="small" pagination={false} rowKey={(_,index)=>String(index)} dataSource={measurements} scroll={{x:900}} columns={[
      {title:"页面",dataIndex:"pageUrl",ellipsis:true},{title:"样本",dataIndex:"sample"},...(["ttfbMs","navigationMs","lcpMs","cls","requestCount","declaredResourceBytes"] as const).map(key=>({title:metricLabel[key],dataIndex:key,render:(v:unknown)=>formatMetric(key,v)}))]}/>
      {Array.isArray(payload.flow)&&<Alert style={{marginTop:12}} type="info" title={`交互步骤：${payload.flow.length} 条，详情见高级证据`}/>}
    </>;
  }
  if(type==="SECURITY"){
    const cases=Array.isArray(payload.apiCases)?payload.apiCases as Record<string,unknown>[]:[];
    const transport=payload.transport as Record<string,unknown>|undefined;
    const zap=payload.zap as Record<string,unknown>|undefined;
    const headers=transport?.headers as Record<string,string[]>|undefined;
    return <><SecurityResultChart cases={cases}/><Descriptions size="small" bordered column={1} items={[{key:"url",label:"检查地址",children:String(transport?.url||"—")},{key:"status",label:"HTTP 状态",children:String(transport?.status||transport?.error||"未完成")},{key:"tls",label:"证书到期",children:String(transport?.certificateExpiresAt||"不适用 / 未取得")},{key:"hsts",label:"HSTS",children:headers?.["strict-transport-security"]?.join(", ")||"未设置 / 不适用"},{key:"csp",label:"CSP",children:headers?.["content-security-policy"]?.join(", ")||"未设置"},{key:"zap",label:"主动扫描",children:zap?.status?`${zap.status}${zap.error?` · ${zap.error}`:""}`:"未执行"}]}/>
      <Table style={{marginTop:12}} size="small" rowKey={(_,index)=>String(index)} dataSource={cases} columns={[{title:"用例",dataIndex:"name",render:(v:unknown)=>String(v||"—")},{title:"检查类型",dataIndex:"kind",render:(v:unknown)=>String(v||"—")},{title:"结果",dataIndex:"status",render:(v:unknown)=><Tag>{String(v||"未知")}</Tag>},{title:"说明",dataIndex:"reason",render:(v:unknown)=>String(v||"—")}]}/>
    </>;
  }
  const summary=payload.summary as Record<string,unknown>|undefined,metrics=summary?.metrics as Record<string,Record<string,unknown>>|undefined;
  const duration=(metrics?.http_req_duration?.values||metrics?.http_req_duration) as Record<string,number>|undefined;
  const request=metrics?.http_reqs as Record<string,number>|undefined;
  const failed=metrics?.http_req_failed as Record<string,number>|undefined;
  const points=Array.isArray(summary?.timeSeries)?summary.timeSeries as Record<string,unknown>[]:[];
  const hasAggregate=typeof request?.count==="number"&&request.count>0;
  const latencyAvailable=performanceAssessment?.latencyAvailable??(hasAggregate&&failed?.value!==1);
  return <><Row gutter={[12,12]}>{[["P50",latencyAvailable?duration?.med:undefined,"ms"],["P95",latencyAvailable?duration?.["p(95)"]:undefined,"ms"],["P99",latencyAvailable?duration?.["p(99)"]:undefined,"ms"],[performanceAssessment?.allRequestsFailed?"请求尝试速率":"吞吐",hasAggregate?request?.rate:undefined,"/秒"],["错误率",hasAggregate&&typeof failed?.value==="number"?failed.value*100:undefined,"%"]].map(([title,number,unit])=><Col xs={12} md={4} key={String(title)}><Statistic title={String(title)} value={typeof number==="number"?number:"—"} precision={2} suffix={String(unit)}/></Col>)}</Row>
    {summary?.firstExceededStage&&<Alert style={{marginTop:12}} type="warning" title={`首次超阈值：第 ${summary.firstExceededStage} 阶段`}/>}
    {!hasAggregate&&points.length>0&&<Alert style={{marginTop:12}} type="info" title="聚合指标未取得；下方仅展示已采集的时间序列"/>}
    <PerformanceTrendChart points={points} assessment={performanceAssessment}/>
    {points.length>0?<Table style={{marginTop:12}} size="small" pagination={false} rowKey={(_,index)=>String(index)} dataSource={points} columns={[{title:"秒",dataIndex:"windowStartSecond"},{title:"阶段",dataIndex:"stage"},{title:"P50",dataIndex:"p50Ms",render:(v:unknown,row:Record<string,unknown>)=>row.errorRate===1?"—":formatMetric("p50Ms",v)},{title:"P95",dataIndex:"p95Ms",render:(v:unknown,row:Record<string,unknown>)=>row.errorRate===1?"—":formatMetric("p95Ms",v)},{title:"P99",dataIndex:"p99Ms",render:(v:unknown,row:Record<string,unknown>)=>row.errorRate===1?"—":formatMetric("p99Ms",v)},{title:performanceAssessment?.allRequestsFailed?"请求尝试/秒":"吞吐/秒",dataIndex:"throughputPerSecond",render:(v:unknown)=>formatMetric("throughputPerSecond",v)},{title:"错误率",dataIndex:"errorRate",render:(v:unknown)=>formatMetric("errorRate",v)}]}/>:<Alert type="warning" title="没有可用时间序列；本模块不能视为性能通过"/>}
  </>;
}

type WorkIssue = {
  id: string;
  severity: string;
  source: string;
  taskStatus: string;
  reviewStatus: string;
  evidenceConfidence: string;
  title: string;
  description: string;
  assigneeName?: string;
  runId: string;
  retestRunId?: string;
  assigneeId?: string;
};
type KnowledgeCase = {
  id: string;
  status: string;
  title: string;
  category?: string;
  tags: string[];
  content: Record<string, unknown>;
  updatedAt: string;
};
type RegressionPlan = {
  id: string;
  targetId: string;
  suiteVersionId: string;
  enabled: boolean;
  cronExpression: string;
  activeScanEnabled: boolean;
  uploadEnabled:boolean;
  loadEnabled: boolean;
  nextRunAt?: string;
};

export function CollaborationPage() {
  const { projectId = "" } = useParams(),
    cache = useQueryClient(),
    { message } = AntApp.useApp(),
    [query, setQuery] = useState(""),
    [planOpen, setPlanOpen] = useState(false),
    [selectedIssue, setSelectedIssue] = useState<WorkIssue>(),
    [reviewIssue,setReviewIssue]=useState<WorkIssue>(),
    [editingPlan,setEditingPlan]=useState<RegressionPlan>(),
    [caseOpen, setCaseOpen] = useState(false),
    [memberOpen,setMemberOpen]=useState(false),
    [explanationIssue, setExplanationIssue] = useState<WorkIssue>(),
    [planForm] = Form.useForm(),[reviewForm]=Form.useForm(),[issueForm]=Form.useForm(),[caseForm]=Form.useForm(),[commentForm]=Form.useForm(),[memberForm]=Form.useForm();
  const [searchParams,setSearchParams]=useSearchParams();
  const statusFilter=searchParams.get('status')||'ALL';
  const requestedIssueId=searchParams.get('issueId');
  const issues = useQuery({
    queryKey: ["work-issues", projectId],
    queryFn: () => request<WorkIssue[]>(`/projects/${projectId}/issues`),
  });
  useEffect(() => {
    const issue = issues.data?.find(item => item.id === requestedIssueId);
    if (issue) {
      setReviewIssue(issue);
      reviewForm.setFieldsValue({decision:issue.reviewStatus === "DISMISSED" ? "DISMISSED" : "CONFIRMED"});
    }
  }, [issues.data, requestedIssueId, reviewForm]);
  const closeReview=()=>{
    setReviewIssue(undefined);
    reviewForm.resetFields();
    if(requestedIssueId)setSearchParams(current=>{const next=new URLSearchParams(current);next.delete('issueId');return next},{replace:true});
  };
  const reviewHistory=useQuery({queryKey:["issue-detail",reviewIssue?.id],queryFn:()=>request<{history:{id:string;action:string;fromStatus:string;toStatus:string;operatorName?:string;createdAt:string;details?:{reason?:string}}[]}>(`/issues/${reviewIssue!.id}`),enabled:!!reviewIssue});
  const cases = useQuery({
    queryKey: ["knowledge-cases", projectId, query],
    queryFn: () =>
      request<KnowledgeCase[]>(
        `/projects/${projectId}/knowledge-cases${query ? `?query=${encodeURIComponent(query)}` : ""}`,
      ),
  });
  const plans = useQuery({
    queryKey: ["regression-plans", projectId],
    queryFn: () =>
      request<RegressionPlan[]>(`/projects/${projectId}/regression-plans`),
  });
  const targets = useQuery({
    queryKey: ["targets", projectId],
    queryFn: () => request<OnlineTarget[]>(`/projects/${projectId}/targets`),
  });
  const suites = useQuery({
    queryKey: ["test-suites", projectId],
    queryFn: () => request<TestSuite[]>(`/projects/${projectId}/test-suites`),
  });
  const members=useQuery({queryKey:['members',projectId],queryFn:()=>request<{userId:string;displayName:string;role:string}[]>(`/projects/${projectId}/members`)});
  const me=useQuery({queryKey:['auth-me'],queryFn:()=>request<{id:string}>('/auth/me')});
  const canManageMembers=members.data?.some(member=>member.userId===me.data?.id&&member.role==='ADMIN');
  const runHistory=useQuery({queryKey:['test-runs',projectId],queryFn:()=>request<TestRun[]>(`/projects/${projectId}/test-runs`)});
  const notifications=useQuery({queryKey:['notifications',projectId],queryFn:()=>request<{id:string;title:string;createdAt:string;readAt?:string}[]>(`/projects/${projectId}/notifications`)});
  const update = useMutation({
    mutationFn: ({ id, status, assigneeId, retestRunId }: { id: string; status: string;assigneeId?:string;retestRunId?:string }) =>
      request<WorkIssue>(`/issues/${id}/status`, {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey() },
        body: JSON.stringify({ status,assigneeId,retestRunId }),
      }),
    onSuccess: () => {
      message.success("任务状态已更新");
      setSelectedIssue(undefined);
      cache.invalidateQueries({ queryKey: ["work-issues", projectId] });
    },
    onError: (e) => message.error((e as Error).message),
  });
  const review=useMutation({mutationFn:(v:{decision:string;reason:string})=>request(`/issues/${reviewIssue!.id}/review`,{method:"POST",headers:{"Idempotency-Key":idempotencyKey()},body:JSON.stringify(v)}),onSuccess:()=>{message.success("可选审阅结论已记录");closeReview();cache.invalidateQueries({queryKey:["work-issues",projectId]});cache.invalidateQueries({queryKey:["issue-detail"]})},onError:e=>message.error((e as Error).message)});
  const addComment=useMutation({mutationFn:({id,body}:{id:string;body:string})=>request(`/issues/${id}/comments`,{method:'POST',body:JSON.stringify({body})}),onSuccess:()=>{message.success('评论已保存');commentForm.resetFields()},onError:e=>message.error((e as Error).message)});
  const createCase=useMutation({mutationFn:(v:{issueId:string;title:string;category:string;tags:string;cause:string;fix:string;verification:string})=>request<KnowledgeCase>(`/projects/${projectId}/knowledge-cases`,{method:'POST',body:JSON.stringify({issueId:v.issueId,title:v.title,category:v.category,tags:v.tags.split(',').map(x=>x.trim()).filter(Boolean),content:{cause:v.cause,fix:v.fix,verification:v.verification}})}),onSuccess:()=>{message.success('案例草稿已保存');setCaseOpen(false);caseForm.resetFields();cache.invalidateQueries({queryKey:['knowledge-cases',projectId]})},onError:e=>message.error((e as Error).message)});
  const publishCase=useMutation({mutationFn:(id:string)=>request(`/knowledge-cases/${id}/publish`,{method:'POST'}),onSuccess:()=>{message.success('案例已发布');cache.invalidateQueries({queryKey:['knowledge-cases',projectId]})},onError:e=>message.error((e as Error).message)});
  const createMember=useMutation({mutationFn:async(v:{username:string;displayName:string;password:string;role:string})=>{return request(`/projects/${projectId}/invite`,{method:'POST',body:JSON.stringify({username:v.username,role:v.role})})},onSuccess:()=>{message.success('成员已加入项目');setMemberOpen(false);memberForm.resetFields();cache.invalidateQueries({queryKey:['members',projectId]})},onError:e=>message.error((e as Error).message)});
  const createPlan = useMutation({
    mutationFn: (v: {
      targetId: string;
      suiteVersionId: string;
      cronExpression?: string;
      activeScanEnabled?: boolean;
      uploadEnabled?:boolean;
      loadEnabled?: boolean;
    }) =>
      request<RegressionPlan>(editingPlan?`/projects/${projectId}/regression-plans/${editingPlan.id}`:`/projects/${projectId}/regression-plans`, {
        method: editingPlan?"PATCH":"POST",
        body: JSON.stringify({...v,activeScanEnabled:!!v.activeScanEnabled,uploadEnabled:!!v.uploadEnabled,loadEnabled:!!v.loadEnabled}),
      }),
    onSuccess: () => {
      message.success("每日回归计划已保存");
      setPlanOpen(false);
      setEditingPlan(undefined);
      planForm.resetFields();
      cache.invalidateQueries({ queryKey: ["regression-plans", projectId] });
    },
    onError: (e) => message.error((e as Error).message),
  });
  const togglePlan=useMutation({mutationFn:(plan:RegressionPlan)=>request<RegressionPlan>(`/projects/${projectId}/regression-plans/${plan.id}`,{method:"PATCH",body:JSON.stringify({enabled:!plan.enabled})}),onSuccess:()=>cache.invalidateQueries({queryKey:["regression-plans",projectId]}),onError:e=>message.error((e as Error).message)});
  const issuePane = (
    <><Select value={statusFilter} onChange={value=>setSearchParams(value==='ALL'?{}:{status:value})} style={{width:190,marginBottom:16}} options={[{value:'ALL',label:'全部状态'},{value:'OPEN',label:'待处理'},{value:'IN_PROGRESS',label:'处理中'},{value:'READY_FOR_RETEST',label:'待复测'},{value:'VERIFIED',label:'已验证'},{value:'REOPENED',label:'重新打开'}]}/><List
      dataSource={(issues.data || []).filter(issue=>statusFilter==='ALL'||issue.taskStatus===statusFilter)}
      locale={{ emptyText: "还没有跨运行修复任务" }}
      renderItem={(issue) => (
        <List.Item
          actions={[
            <Link key="repair" to={`/issues/${issue.id}/repair`}><Button type="primary">开始修复</Button></Link>,
            <Button key="review" onClick={()=>{setReviewIssue(issue);reviewForm.setFieldsValue({decision:issue.reviewStatus==="DISMISSED"?"DISMISSED":"CONFIRMED"})}}>可选审阅</Button>,
            <Button key="status" onClick={()=>{setSelectedIssue(issue);issueForm.setFieldsValue({status:issue.taskStatus,assigneeId:issue.assigneeId})}}>分配 / 复测</Button>,
            <Button key="case" onClick={()=>{caseForm.setFieldsValue({issueId:issue.id,title:issue.title,category:issue.source});setCaseOpen(true)}}>沉淀案例</Button>,
            <Button
              key="ai"
              type="link"
              onClick={() => setExplanationIssue(issue)}
            >
              原因解释
            </Button>,
            <Link key="run" to={`/test-runs/${issue.runId}`}>
              查看运行
            </Link>,
          ]}
        >
          <List.Item.Meta
            title={
              <Space>
                <Tag
                  color={
                    issue.severity === "HIGH"
                      ? "red"
                      : issue.severity === "MEDIUM"
                        ? "orange"
                        : "blue"
                  }
                >
                  {issue.severity}
                </Tag>
                {issue.title}
              </Space>
            }
            description={
              <>
                <div>{issue.description}</div>
                <Space>
                  <Typography.Text type="secondary">
                    {issue.source}
                  </Typography.Text>
                  <Tag>{issue.evidenceConfidence}</Tag>
                  <Tag color={issue.reviewStatus==="CONFIRMED"?"green":issue.reviewStatus==="DISMISSED"?"default":"gold"}>审阅：{optionalReviewName[issue.reviewStatus] || issue.reviewStatus}</Tag>
                  <Tag>{issue.taskStatus}</Tag>
                  {issue.assigneeName && (
                    <span>负责人：{issue.assigneeName}</span>
                  )}
                </Space>
              </>
            }
          />
        </List.Item>
      )}
    /></>
  );
  const casePane = (
    <>
      <Input.Search
        allowClear
        placeholder="按标题、规则、分类或标签检索"
        onSearch={setQuery}
        style={{ maxWidth: 460, marginBottom: 16 }}
      />
      <List
        dataSource={cases.data || []}
        locale={{ emptyText: "还没有已沉淀的案例" }}
        renderItem={(item) => (
          <List.Item actions={item.status==='DRAFT'?[<Button key="publish" type="link" loading={publishCase.isPending} onClick={()=>publishCase.mutate(item.id)}>发布已验证案例</Button>]:[]}>
            <List.Item.Meta
              title={
                <Space>
                  {item.title}
                  <Tag
                    color={item.status === "PUBLISHED" ? "green" : "default"}
                  >
                    {item.status === "PUBLISHED" ? "已发布" : "草稿"}
                  </Tag>
                </Space>
              }
              description={
                <>
                  {item.category && <Tag>{item.category}</Tag>}
                  {Array.isArray(item.tags) &&
                    item.tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}
                  <div>
                    <Typography.Text type="secondary">
                      更新于 {new Date(item.updatedAt).toLocaleString()}
                    </Typography.Text>
                  </div>
                </>
              }
            />
          </List.Item>
        )}
      />
    </>
  );
  const planPane = (
    <>
      <Button
        type="primary"
        icon={<PlusOutlined />}
        onClick={() => setPlanOpen(true)}
        style={{ marginBottom: 16 }}
      >
        新建每日回归
      </Button>
      <List
        dataSource={plans.data || []}
        locale={{ emptyText: "还没有回归计划" }}
        renderItem={(item) => (
          <List.Item actions={[<Button key="edit" onClick={()=>{setEditingPlan(item);planForm.setFieldsValue(item);setPlanOpen(true)}}>配置</Button>,<Button key="toggle" loading={togglePlan.isPending} onClick={()=>togglePlan.mutate(item)}>{item.enabled?"停用":"启用"}</Button>] }>
            <List.Item.Meta
              title={
                <Space>
                  每日回归
                  <Tag color={item.enabled ? "green" : "default"}>
                    {item.enabled ? "启用" : "停用"}
                  </Tag>
                </Space>
              }
              description={
                <Space wrap>
                  <span>{item.cronExpression}</span>
                  {item.activeScanEnabled && <Tag color="orange">主动扫描</Tag>}
                  {item.uploadEnabled && <Tag color="blue">上传检查</Tag>}
                  {item.loadEnabled && <Tag color="purple">负载测试</Tag>}
                  <Typography.Text type="secondary">
                    下次：
                    {item.nextRunAt
                      ? new Date(item.nextRunAt).toLocaleString()
                      : "待调度"}
                  </Typography.Text>
                </Space>
              }
            />
          </List.Item>
        )}
      />
    </>
  );
  const changeMember=useMutation({mutationFn:({id,role}:{id:string;role?:string})=>request(`/projects/${projectId}/members/${id}`,{method:role?'PATCH':'DELETE',body:role?JSON.stringify({role}):undefined}),onSuccess:()=>{void cache.invalidateQueries({queryKey:['members',projectId]});void cache.invalidateQueries({queryKey:['auth-me']});message.success('项目成员已更新')},onError:(e:Error)=>message.error(e.message)});
  const memberPane=<><Button disabled={!canManageMembers} onClick={()=>setMemberOpen(true)} style={{marginBottom:16}}>添加已注册成员</Button>{members.isError?<Alert type="error" title="成员加载失败" action={<Button onClick={()=>void members.refetch()}>重试</Button>}/>:<List dataSource={members.data||[]} renderItem={member=><List.Item><List.Item.Meta title={member.displayName} description={member.role==='ADMIN'?'项目管理员':member.role==='MEMBER'?'项目成员':'只读访问者'}/>{canManageMembers&&<Space><Select aria-label={`修改 ${member.displayName} 的角色`} value={member.role} disabled={changeMember.isPending} options={[{value:'ADMIN',label:'管理员'},{value:'MEMBER',label:'成员'},{value:'VIEWER',label:'只读'}]} onChange={role=>changeMember.mutate({id:member.userId,role})}/><Button danger disabled={changeMember.isPending} onClick={()=>Modal.confirm({title:`移除 ${member.displayName}？`,content:'该成员将失去项目访问权限，历史记录保留。',okText:'移除',cancelText:'取消',onOk:()=>changeMember.mutateAsync({id:member.userId})})}>移除</Button></Space>}</List.Item>}/>}</>;
  return (
    <>
      <Head
        title="修复回归闭环"
        subtitle="分配修复、沉淀案例并安排版本化回归。已验证状态必须通过任务详情关联有效复测结果。"
        back={`/projects/${projectId}/quality`}
      />
      <Card>
        <Tabs
          items={[
            {
              key: "issues",
              label: `修复任务 ${issues.data?.length || 0}`,
              children: issuePane,
            },
            {
              key: "cases",
              label: `案例库 ${cases.data?.length || 0}`,
              children: casePane,
            },
            {
              key: "plans",
              label: `回归计划 ${plans.data?.length || 0}`,
              children: planPane,
            },
            {key:'notifications',label:`提醒 ${notifications.data?.filter(item=>!item.readAt).length||0}`,children:<List dataSource={notifications.data||[]} renderItem={item=><List.Item><List.Item.Meta title={item.title} description={new Date(item.createdAt).toLocaleString()}/></List.Item>}/>},
            {key:'members',label:`团队成员 ${members.data?.length||0}`,children:memberPane},
          ]}
        />
      </Card>
      <Modal title="添加项目成员" open={memberOpen} onCancel={()=>setMemberOpen(false)} onOk={()=>memberForm.submit()} confirmLoading={createMember.isPending}><Form form={memberForm} layout="vertical" initialValues={{role:'MEMBER'}} onFinish={v=>createMember.mutate(v)}><Form.Item name="username" label="登录名" rules={[{required:true}]}><Input autoComplete="off"/></Form.Item><Form.Item name="role" label="项目角色" rules={[{required:true}]}><Select options={[{value:'ADMIN',label:'管理员'},{value:'MEMBER',label:'成员'},{value:'VIEWER',label:'只读访问者'}]}/></Form.Item></Form></Modal>
      <Modal title={reviewIssue?`可选审阅 · ${reviewIssue.title}`:"可选审阅"} open={!!reviewIssue} onCancel={closeReview} onOk={()=>reviewForm.submit()} confirmLoading={review.isPending}><Form form={reviewForm} layout="vertical" onFinish={v=>review.mutate(v)}><Form.Item name="decision" label="结论" rules={[{required:true}]}><Select options={[{value:"CONFIRMED",label:"记录为问题"},{value:"DISMISSED",label:"驳回候选"}]}/></Form.Item><Form.Item name="reason" label="审阅理由（将记录操作者和历史）" rules={[{required:true,whitespace:true,max:2000}]}><Input.TextArea rows={4}/></Form.Item><Alert type="info" title="此步骤不再是发布门槛；审阅结论与修复任务状态独立，AI 也不会自动确认漏洞。"/></Form><Divider>审阅历史</Divider><List size="small" dataSource={reviewHistory.data?.history.filter(item=>item.action==="REVIEWED")||[]} locale={{emptyText:"暂无审阅记录"}} renderItem={item=><List.Item><List.Item.Meta title={`${item.operatorName||"项目成员"} · ${new Date(item.createdAt).toLocaleString()} · ${item.toStatus}`} description={item.details?.reason||"—"}/></List.Item>}/></Modal>
      <Modal title={selectedIssue?.title||'修复任务'} open={!!selectedIssue} onCancel={()=>setSelectedIssue(undefined)} onOk={()=>issueForm.submit()} confirmLoading={update.isPending}>
        <Form form={issueForm} layout="vertical" onFinish={v=>selectedIssue&&update.mutate({id:selectedIssue.id,...v})}>
          <Form.Item name="status" label="任务状态" rules={[{required:true}]}><Select options={[{value:'OPEN',label:'待处理'},{value:'IN_PROGRESS',label:'处理中'},{value:'READY_FOR_RETEST',label:'待复测'},{value:'VERIFIED',label:'已验证（须提供有效复测）'},{value:'REOPENED',label:'重新打开'}]}/></Form.Item>
          <Form.Item name="assigneeId" label="负责人"><Select allowClear options={members.data?.map(member=>({value:member.userId,label:member.displayName}))}/></Form.Item>
          <Form.Item name="retestRunId" label="复测运行"><Select allowClear options={runHistory.data?.filter(run=>run.status==='COMPLETED'&&run.createdAt>(selectedIssue?.runId?runHistory.data?.find(old=>old.id===selectedIssue.runId)?.createdAt||'':'' )).map(run=>({value:run.id,label:`${new Date(run.createdAt).toLocaleString()} · ${run.id.slice(0,8)}`}))}/></Form.Item>
        </Form>
        <Divider>协作评论</Divider><Form form={commentForm} onFinish={v=>selectedIssue&&addComment.mutate({id:selectedIssue.id,body:v.body})}><Form.Item name="body" rules={[{required:true}]}><Input.TextArea rows={2} placeholder="记录修复进展与验证说明"/></Form.Item><Button onClick={()=>commentForm.submit()} loading={addComment.isPending}>添加评论</Button></Form>
      </Modal>
      <Modal title="沉淀修复案例" open={caseOpen} onCancel={()=>setCaseOpen(false)} onOk={()=>caseForm.submit()} confirmLoading={createCase.isPending}>
        <Form form={caseForm} layout="vertical" onFinish={v=>createCase.mutate(v)}><Form.Item name="issueId" hidden><Input/></Form.Item><Form.Item name="title" label="案例标题" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="category" label="分类"><Input/></Form.Item><Form.Item name="tags" label="标签（逗号分隔）"><Input/></Form.Item><Form.Item name="cause" label="原因" rules={[{required:true}]}><Input.TextArea rows={2}/></Form.Item><Form.Item name="fix" label="实际修复方式" rules={[{required:true}]}><Input.TextArea rows={2}/></Form.Item><Form.Item name="verification" label="验证步骤与结果" rules={[{required:true}]}><Input.TextArea rows={2}/></Form.Item><Alert type="info" showIcon title="先保存草稿；关联任务经有效复测标记为已验证后，才能发布。"/></Form>
      </Modal>
      <IssueExplanationModal issue={explanationIssue} onClose={()=>setExplanationIssue(undefined)}/>
      <Modal
        title={editingPlan?"修改每日回归计划":"新建每日回归计划"}
        open={planOpen}
        onCancel={() => {setPlanOpen(false);setEditingPlan(undefined);planForm.resetFields()}}
        onOk={() => planForm.submit()}
        confirmLoading={createPlan.isPending}
      >
        <Form
          form={planForm}
          layout="vertical"
          initialValues={{ cronExpression: "0 0 2 * * *" }}
          onFinish={(v) => createPlan.mutate(v)}
        >
          <Form.Item name="targetId" label="目标" rules={[{ required: true }]}>
            <Select
              disabled={!!editingPlan}
              options={targets.data?.map((t) => ({
                value: t.id,
                label: t.name,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="suiteVersionId"
            label="套件版本"
            rules={[{ required: true }]}
          >
            <Select
              disabled={!!editingPlan}
              options={suites.data?.map((s) => ({
                value: s.currentVersionId,
                label: `${s.name} · V${s.currentVersion}`,
              }))}
            />
          </Form.Item>
          <Form.Item name="cronExpression" label="执行时间（Spring Cron）">
            <Input />
          </Form.Item>
          <Form.Item name="activeScanEnabled" valuePropName="checked">
            <Checkbox>每日执行主动漏洞扫描</Checkbox>
          </Form.Item>
          <Form.Item name="uploadEnabled" valuePropName="checked"><Checkbox>每日执行上传安全检查（目标必须允许写测试）</Checkbox></Form.Item>
          <Form.Item name="loadEnabled" valuePropName="checked">
            <Checkbox>每日执行负载测试</Checkbox>
          </Form.Item>
          <Alert
            type="info"
            showIcon
            title="默认日常回归只运行页面、接口断言和安全配置检查；主动扫描和负载测试需单独启用。"
          />
        </Form>
      </Modal>
    </>
  );
}
