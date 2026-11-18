"""Package repository-owned hosting fixtures; never executes their acceptance scripts."""
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
root=Path(__file__).resolve().parents[1];output=root/'output/hosting-fixtures';output.mkdir(parents=True,exist_ok=True)
for folder in (root/'fixtures/hosting').iterdir():
    if folder.is_dir():
        with ZipFile(output/(folder.name+'.zip'),'w',ZIP_DEFLATED) as archive:
            for file in folder.rglob('*'):
                if file.is_file() and not any(p in ['node_modules','target','.git'] for p in file.parts): archive.write(file,file.relative_to(folder))
print(str(output))
