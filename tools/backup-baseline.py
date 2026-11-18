"""Create a private local rollback baseline without printing credentials."""
from pathlib import Path
from datetime import datetime
import json, os, shutil, subprocess, zipfile

root = Path(__file__).resolve().parents[1]
out = root / 'output' / 'stability' / ('baseline-' + datetime.now().strftime('%Y%m%d%H%M%S'))
out.mkdir(parents=True)
with zipfile.ZipFile(out / 'source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for directory in ['backend/src', 'frontend/src', 'tools', 'md', 'fixtures']:
        for file in (root / directory).rglob('*'):
            if file.is_file() and '__pycache__' not in file.parts:
                archive.write(file, file.relative_to(root))
    for pattern in ['*.ps1', '*.md', '*.yml', '.env.example', '.gitignore', 'backend/pom.xml', 'frontend/package*.json', 'frontend/tsconfig*.json', 'frontend/vite.config.ts']:
        for file in root.glob(pattern):
            if file.is_file(): archive.write(file, file.relative_to(root))
if (root / '.env').exists(): shutil.copy2(root / '.env', out / 'private.env')
jar = root / 'backend/target/accessibility-0.9.0-SNAPSHOT.jar'
if jar.exists(): shutil.copy2(jar, out / jar.name)
env = {**os.environ, 'DOCKER_CONFIG': str(root / '.runtime/docker-cli')}
docker = r'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
with (out / 'platform.sql').open('wb') as dump:
    subprocess.run([docker, 'exec', 'shijie-mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --triggers --databases shijie_accessibility'], env=env, stdout=dump, stderr=subprocess.PIPE, check=True)
(out / 'baseline.json').write_text(json.dumps({'createdAt': datetime.now().isoformat(), 'source': 'source.zip', 'database': 'platform.sql', 'privateConfiguration': 'private.env', 'userVolumesUnchanged': True}, indent=2), encoding='utf-8')
print(str(out))
