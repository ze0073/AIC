"""Upgrade a private baseline copy from V17 to V18, with all workers disabled."""
import json,os,re,subprocess,time,urllib.request
from datetime import datetime
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
env={**os.environ,'DOCKER_CONFIG':str(ROOT/'.runtime/docker-cli')}
for line in (ROOT/'.env').read_text(encoding='utf-8-sig').splitlines():
    match=re.fullmatch(r'([A-Za-z_][A-Za-z0-9_]*)=(.*)',line)
    if match: env[match[1]]=match[2].strip().strip('"\'')
docker=r'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
schema='shijie_upgrade_qa_'+datetime.now().strftime('%Y%m%d%H%M%S')
assert re.fullmatch(r'shijie_upgrade_qa_\d{14}',schema)
output=ROOT/'output/stability'/schema;output.mkdir(parents=True)
baseline=ROOT/'output/stability/baseline-20261004205844/platform.sql'
dump=baseline.read_bytes().replace(b'`shijie_accessibility`',('`'+schema+'`').encode())
assert b'`shijie_accessibility`' not in dump
def sql(command=None,content=None):
    args=[docker,'exec','-i','shijie-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N']
    result=subprocess.run(args,input=content if content is not None else command.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,timeout=60)
    if result.returncode: raise RuntimeError('隔离数据库操作失败')
    return result.stdout.decode('utf-8').strip()
tables=['projects','project_versions','app_users','project_members','guide_sessions','scan_tasks','test_runs','notifications']
def counts(): return {table:int(sql('SELECT COUNT(*) FROM '+schema+'.'+table)) for table in tables}
app=None
try:
    sql(content=dump)
    user=env.get('SPRING_DATASOURCE_USERNAME','shijie');assert re.fullmatch(r'[A-Za-z0-9_]+',user)
    sql("GRANT ALL ON "+schema+".* TO '"+user+"'@'%'; USE "+schema+"; UPDATE regression_plans SET enabled=FALSE; UPDATE scan_tasks SET status='CANCELLED' WHERE status IN ('PENDING','RUNNING'); UPDATE test_runs SET status='CANCELLED' WHERE status IN ('PENDING','RUNNING'); UPDATE agent_tasks SET status='CANCELLED' WHERE status='RUNNING';")
    assert sql('SELECT version FROM '+schema+'.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1')=='17'
    before=counts()
    env.update({'SERVER_PORT':'18084','SERVER_BIND_HOST':'127.0.0.1','APP_HOSTING_GATEWAY_PORT':'18094','APP_HOSTING_WORKER_ENABLED':'false','AI_ENABLED':'false','APP_STORAGE_ROOT':str(output/'storage'),'SPRING_DATASOURCE_URL':re.sub(r'/[^/?]+(?=\?|$)','/'+schema,env['SPRING_DATASOURCE_URL'])})
    java=next((ROOT/'.runtime/jdk-21').glob('jdk-*/bin/java.exe'))
    with (output/'application.log').open('wb') as log:
        app=subprocess.Popen([str(java),'-Xmx512m','-jar',str(ROOT/'backend/target-stability/accessibility-0.9.0-SNAPSHOT.jar'),'--app.hosting.worker-enabled=false'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        deadline=time.monotonic()+90
        while True:
            if app.poll() is not None: raise RuntimeError('隔离升级应用退出，请检查本地日志')
            try:
                with urllib.request.urlopen('http://127.0.0.1:18084/actuator/health',timeout=2) as response:
                    if response.status==200: break
            except Exception:
                if time.monotonic()>deadline: raise RuntimeError('隔离升级应用启动超时')
                time.sleep(1)
        after=counts();assert before==after
        assert sql('SELECT version FROM '+schema+'.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1')=='18'
        result={'migration':'V17_TO_V18_PASSED','rowCountsPreserved':after,'userStorageTouched':False,'database':schema}
        (output/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(result,ensure_ascii=False,indent=2))
finally:
    if app and app.poll() is None: app.terminate();app.wait(timeout=20)
    sql('DROP DATABASE IF EXISTS '+schema)
