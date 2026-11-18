"""Inspect and restore a Shijie backup into NEW, labelled Docker resources only.

Usage: python tools/restore-backup.py inspect backup.zip
       python tools/restore-backup.py restore backup.zip [--engine MYSQL] [--image IMAGE]
       python tools/restore-backup.py cleanup output/restore/ID/resources.json
"""
from pathlib import Path, PurePosixPath
from datetime import datetime
import argparse, hashlib, io, json, os, re, secrets, subprocess, tarfile, time, uuid, zipfile

ROOT = Path(__file__).resolve().parents[1]
DOCKER = r'C:\Program Files\Docker\Docker\resources\bin\docker.exe' if os.name == 'nt' else 'docker'
ENV = {**os.environ, 'DOCKER_CONFIG': str(ROOT / '.runtime/docker-cli')}
LIMIT = 100 * 1024 * 1024
ALLOWED = {'website-files.tar.gz', 'database.sql', 'source.zip', 'backup-info.txt', 'manifest.json'}

def run(*args, input=None, timeout=90):
    result = subprocess.run([DOCKER, *map(str,args)], input=input, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=ENV, timeout=timeout)
    if result.returncode: raise RuntimeError('Docker 操作未完成：' + args[0])
    return result.stdout.decode('utf-8', errors='replace').strip()

def safe_name(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or '..' in path.parts or '\\' in name or ':' in name or '\0' in name:
        raise ValueError('备份包含不安全路径')

def validate_tar(content):
    total, count = 0, 0
    with tarfile.open(fileobj=io.BytesIO(content), mode='r:gz') as archive:
        for entry in archive:
            count += 1; safe_name(entry.name)
            if not (entry.isfile() or entry.isdir()): raise ValueError('恢复文件不能包含链接或特殊设备')
            total += entry.size
            if count > 10000 or total > LIMIT: raise ValueError('恢复文件超出 100 MB 或 10000 项限制')
    return {'fileCount': count, 'expandedBytes': total}

def inspect(file):
    file = Path(file).resolve()
    if file.stat().st_size > 350 * 1024 * 1024: raise ValueError('备份包超过大小限制')
    with zipfile.ZipFile(file) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or not set(names) <= ALLOWED: raise ValueError('备份包含重复或未知条目')
        if 'website-files.tar.gz' not in names: raise ValueError('缺少网站文件归档')
        if sum(e.file_size for e in archive.infolist()) > 350 * 1024 * 1024: raise ValueError('解压大小超出限制')
        if 'manifest.json' in names and archive.getinfo('manifest.json').file_size > 1024*1024: raise ValueError('清单过大')
        manifest = json.loads(archive.read('manifest.json')) if 'manifest.json' in names else {}
        if manifest and (manifest.get('format') != 'shijie-full-backup' or manifest.get('schemaVersion') != 1): raise ValueError('不支持的备份格式')
        expected = {entry['name']: entry for entry in manifest.get('files', [])}
        data = {name: archive.read(name) for name in names if name not in {'manifest.json', 'backup-info.txt'}}
        if manifest and set(expected) != set(data): raise ValueError('清单与实际文件不一致')
        for name, content in data.items():
            if name in expected and (expected[name]['size'] != len(content) or expected[name]['sha256'] != hashlib.sha256(content).hexdigest()): raise ValueError('备份摘要或大小不匹配：' + name)
        files = validate_tar(data['website-files.tar.gz'])
        if 'source.zip' in data:
            with zipfile.ZipFile(io.BytesIO(data['source.zip'])) as source:
                if len(source.infolist()) > 500 or sum(e.file_size for e in source.infolist()) > LIMIT: raise ValueError('源码超出限制')
                for entry in source.infolist(): safe_name(entry.filename)
                if source.testzip(): raise ValueError('源码归档校验失败')
        summary = {'integrity': 'SHA256_VERIFIED' if manifest else 'LEGACY_CRC_AND_PATHS', 'databaseEngine': manifest.get('databaseEngine'), 'hasDatabase': 'database.sql' in data, 'hasSource': 'source.zip' in data, 'consistency': manifest.get('consistency', 'UNKNOWN'), **files}
        return summary, manifest, data

def resources_file(out, token, containers, volumes, networks):
    target = out / 'resources.json'
    target.write_text(json.dumps({'restoreId':token,'containers':containers,'volumes':volumes,'networks':networks}, indent=2), encoding='utf-8')
    return target

def cleanup(file):
    state=json.loads(Path(file).read_text(encoding='utf-8'));token=state['restoreId']
    if not re.fullmatch('[a-f0-9]{32}',token): raise ValueError('恢复资源标识无效')
    for kind, key in [('container','containers'),('volume','volumes'),('network','networks')]:
        for name in state[key]:
            if not name.startswith('sj-restore-'+token): raise ValueError('资源不属于此恢复任务')
            try: result=json.loads(run(kind,'inspect',name))[0]
            except RuntimeError: continue
            labels=result.get('Config',{}).get('Labels',{}) if kind=='container' else result.get('Labels',{})
            if (labels or {}).get('shijie.restore') != token: raise ValueError('资源所有权校验失败')
            run(kind,'rm',*(['--force'] if kind=='container' else []),name)
    return {'cleaned':True,'restoreId':token}

def restore(args):
    summary,manifest,data=inspect(args.backup)
    engine=args.engine or manifest.get('databaseEngine') or ('NONE' if not summary['hasDatabase'] else None)
    if not engine: raise ValueError('旧备份含 SQL，请使用 --engine MYSQL 或 POSTGRESQL')
    if manifest.get('databaseEngine') and engine != manifest['databaseEngine']: raise ValueError('数据库类型与清单不一致')
    if engine=='NONE' and summary['hasDatabase']: raise ValueError('SQL 备份必须选择数据库类型')
    image=args.image or manifest.get('image')
    if not image or image.startswith('-') or re.search(r'\s',image): raise ValueError('需要清单中的本机镜像，或使用 --image 指定兼容镜像')
    run('image','inspect',image)
    token=uuid.uuid4().hex;prefix='sj-restore-'+token;out=ROOT/'output'/'restore'/token;out.mkdir(parents=True)
    containers=[];volumes=[];networks=[]
    def save(): return resources_file(out,token,containers,volumes,networks)
    label='shijie.restore='+token
    def volume(name):
        run('volume','create','--label',label,name);volumes.append(name);save()
    network=prefix+'-internal'
    pool=os.environ.get('APP_HOSTING_NETWORK_PREFIX','10.253')
    if not re.fullmatch(r'10\.(?:[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])',pool): raise ValueError('隔离网络前缀必须为 10.x')
    for attempt in range(64):
        slot=(int(token[:8],16)+attempt)%4096
        try:
            run('network','create','--internal','--subnet',f'{pool}.{slot//16}.{slot%16*16}/28','--label',label,network);break
        except RuntimeError:
            if attempt==63: raise
    networks.append(network);save()
    volume(prefix+'-files')
    archive=out/'website-files.tar.gz';archive.write_bytes(data['website-files.tar.gz'])
    helper=prefix+'-import';containers.append(helper);save()
    run('run','--detach','--name',helper,'--label',label,'--network','none','--read-only','--cap-drop','ALL','--cap-add','CHOWN','--tmpfs','/tmp:rw,size=128m','--mount',f'type=volume,src={prefix}-files,dst=/data','busybox:1.37','sleep','300')
    run('exec','-i',helper,'tar','-C','/data','-xzf','-',input=data['website-files.tar.gz'])
    run('exec',helper,'chown','-R','10001:10001','/data');run('rm','--force',helper);containers.remove(helper);save()
    config=manifest.get('runtimeConfig') or {};port=int(args.port or config.get('port') or 8080)
    if not 1 <= port <= 65535: raise ValueError('应用端口无效')
    environment={'PORT':str(port),'SERVER_PORT':str(port),'HOST':'0.0.0.0','HOME':'/tmp','NPM_CONFIG_CACHE':'/tmp/npm-cache','DATA_DIRECTORY':'/data'}
    if args.env_file:
        for line in Path(args.env_file).read_text(encoding='utf-8-sig').splitlines():
            if not line.strip() or line.startswith('#'): continue
            key,value=line.split('=',1)
            if not re.fullmatch('[A-Za-z_][A-Za-z0-9_]*',key): raise ValueError('环境变量名无效')
            environment[key]=value
    envfile=out/'private-runtime.env'
    db=prefix+'-database'
    try:
        if engine!='NONE':
            password=secrets.token_hex(24);volume(prefix+'-database')
            dbvars={'MYSQL_DATABASE':'site','MYSQL_USER':'site','MYSQL_PASSWORD':password,'MYSQL_ROOT_PASSWORD':secrets.token_hex(24)} if engine=='MYSQL' else {'POSTGRES_DB':'site','POSTGRES_USER':'site','POSTGRES_PASSWORD':password}
            envfile.write_text(''.join(k+'='+v+'\n' for k,v in dbvars.items()),encoding='utf-8')
            containers.append(db);save();run('run','--detach','--name',db,'--label',label,'--network',network,'--network-alias','database','--memory','1g','--cpus','1','--pids-limit','256','--env-file',envfile,'--mount',f'type=volume,src={prefix}-database,dst='+('/var/lib/mysql' if engine=='MYSQL' else '/var/lib/postgresql/data'),'mysql:8.4' if engine=='MYSQL' else 'postgres:16-alpine')
            query=['exec',db,'sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql --user=site --database=site --execute="SELECT 1"'] if engine=='MYSQL' else ['exec',db,'pg_isready','-U','site','-d','site']
            deadline=time.monotonic()+90
            while True:
                try: run(*query,timeout=4);break
                except (RuntimeError,subprocess.TimeoutExpired):
                    if time.monotonic()>deadline: raise RuntimeError('恢复数据库未就绪')
                    time.sleep(1)
            if 'database.sql' in data:
                restorecmd=['exec','-i',db,'sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user=site site'] if engine=='MYSQL' else ['exec','-i',db,'psql','-U','site','-d','site','--set','ON_ERROR_STOP=1']
                run(*restorecmd,input=data['database.sql'],timeout=120)
            dbport='3306' if engine=='MYSQL' else '5432';scheme='mysql' if engine=='MYSQL' else 'postgresql'
            environment.update({'DB_HOST':'database','DB_PORT':dbport,'DB_NAME':'site','DB_USER':'site','DB_PASSWORD':password,'DATABASE_URL':f'{scheme}://site:{password}@database:{dbport}/site','SPRING_DATASOURCE_URL':f'jdbc:{scheme}://database:{dbport}/site','SPRING_DATASOURCE_USERNAME':'site','SPRING_DATASOURCE_PASSWORD':password})
        envfile.write_text(''.join(k+'='+v+'\n' for k,v in environment.items()),encoding='utf-8')
        app=prefix+'-app';containers.append(app);save()
        run('run','--detach','--name',app,'--label',label,'--network',network,'--network-alias','backend','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--user','10001:10001','--memory','1g','--memory-swap','1g','--cpus','1','--pids-limit','128','--tmpfs','/tmp:rw,noexec,nosuid,size=128m','--env-file',envfile,'--mount',f'type=volume,src={prefix}-files,dst=/data',image)
        frontend=manifest.get('frontendImage');upstream='backend';upstream_port=port
        if frontend and (frontend.startswith('-') or re.search(r'\s',frontend)): raise ValueError('前端镜像标识无效')
        if frontend:
            web=prefix+'-web';containers.append(web);save();run('run','--detach','--name',web,'--label',label,'--network',network,'--network-alias','frontend','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--user','10001:10001','--memory','128m','--pids-limit','64','--tmpfs','/tmp:rw,size=64m',frontend);upstream='frontend';upstream_port=8080
        ingress=args.ingress_image or run('inspect','--format','{{.Config.Image}}','sj-build-egress')
        if ingress.startswith('-') or re.search(r'\s',ingress): raise ValueError('入口镜像标识无效')
        entry=prefix+'-entry';containers.append(entry);save();run('run','--detach','--name',entry,'--label',label,'--network','bridge','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--user','10001:10001','--memory','128m','--pids-limit','64','--env','UPSTREAM_HOST='+upstream,'--env','UPSTREAM_PORT='+str(upstream_port),'--publish','127.0.0.1::8080',ingress,'python','/ingress.py');run('network','connect',network,entry)
        bound=run('port',entry,'8080/tcp').splitlines()[0];url='http://'+bound+'/'
        import urllib.request
        deadline=time.monotonic()+90;health=config.get('healthPath') or '/'
        while True:
            try:
                with urllib.request.urlopen(url.rstrip('/')+health,timeout=3) as response:
                    if response.status==200: break
            except Exception:
                if time.monotonic()>deadline: raise RuntimeError('恢复环境健康检查未通过，资源已保留，可检查后清理')
                time.sleep(1)
        result={**summary,'databaseEngine':engine,'restoreId':token,'url':url,'resources':str(save()),'health':'PASSED','originalResourcesUnchanged':True}
        (out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        if 'source.zip' in data: (out/'source.zip').write_bytes(data['source.zip'])
        return result
    finally:
        envfile.unlink(missing_ok=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__);commands=parser.add_subparsers(dest='command',required=True)
    check=commands.add_parser('inspect');check.add_argument('backup')
    launch=commands.add_parser('restore');launch.add_argument('backup');launch.add_argument('--engine',choices=['MYSQL','POSTGRESQL','NONE']);launch.add_argument('--image');launch.add_argument('--port',type=int);launch.add_argument('--env-file');launch.add_argument('--ingress-image')
    remove=commands.add_parser('cleanup');remove.add_argument('state')
    args=parser.parse_args()
    try:
        result=inspect(args.backup)[0] if args.command=='inspect' else cleanup(args.state) if args.command=='cleanup' else restore(args)
        print(json.dumps(result,ensure_ascii=False,indent=2))
    except Exception as ex:
        print(json.dumps({'error':str(ex)},ensure_ascii=False));raise SystemExit(1)

if __name__=='__main__': main()
