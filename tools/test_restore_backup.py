"""Validate archive rejection boundaries without starting Docker."""
import hashlib, importlib.util, io, json, tarfile, tempfile, unittest, zipfile
from pathlib import Path
spec=importlib.util.spec_from_file_location('restore_backup',Path(__file__).with_name('restore-backup.py'))
restore=importlib.util.module_from_spec(spec);spec.loader.exec_module(restore)
class BackupValidationTest(unittest.TestCase):
    def archive(self,name='proof.txt',link=False):
        out=io.BytesIO()
        with tarfile.open(fileobj=out,mode='w:gz') as tar:
            entry=tarfile.TarInfo(name);entry.size=5
            if link: entry.type=tarfile.SYMTYPE;entry.linkname='../outside';entry.size=0
            tar.addfile(entry,None if link else io.BytesIO(b'proof'))
        return out.getvalue()
    def inspect(self,content,sha=None):
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder)/'backup.zip'
            with zipfile.ZipFile(target,'w') as zip:
                zip.writestr('website-files.tar.gz',content)
                zip.writestr('manifest.json',json.dumps({'format':'shijie-full-backup','schemaVersion':1,'files':[{'name':'website-files.tar.gz','size':len(content),'sha256':sha or hashlib.sha256(content).hexdigest()}]}))
            return restore.inspect(target)[0]
    def test_valid_payload(self): self.assertEqual(self.inspect(self.archive())['expandedBytes'],5)
    def test_tampered_digest(self):
        with self.assertRaises(ValueError): self.inspect(self.archive(),sha='0'*64)
    def test_path_traversal(self):
        with self.assertRaises(ValueError): self.inspect(self.archive('../outside.txt'))
    def test_symbolic_link(self):
        with self.assertRaises(ValueError): self.inspect(self.archive(link=True))
if __name__=='__main__': unittest.main()
