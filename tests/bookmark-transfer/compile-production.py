"""Verify mapped API linkage using local cached dependencies; no Gradle."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
p=argparse.ArgumentParser();p.add_argument('--java-home',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[2]
mc=next((root/'.gradle/loom-cache/minecraftMaven').rglob('minecraft-merged-60e7074716-*.jar'))
libs=list((Path.home()/'.gradle/caches/modules-2/files-2.1').rglob('*.jar'))
cp=os.pathsep.join(map(str,[root/'xplat/build/classes/java/main',mc,*libs]))
with tempfile.TemporaryDirectory(prefix='emi-transfer-linkage-') as d:
    args=Path(d)/'args.txt'
    args.write_text('--release\n17\n-encoding\nUTF-8\n-proc:none\n-cp\n"'+cp.replace('\\','/')+'"\n-d\n"'+str(Path(d)/'classes').replace('\\','/')+'"\n"'+str(root/'xplat/src/main/java/dev/emi/emi/runtime/BookmarkMaterialTransfer.java').replace('\\','/')+'"\n',encoding='utf-8')
    with args.open('a', encoding='utf-8') as f:
        f.write('"'+str(root/'xplat/src/main/java/dev/emi/emi/runtime/EmiLog.java').replace('\\','/')+'"\n')
    subprocess.run([str(a.java_home/'bin/javac.exe'),'@'+str(args)],check=True)
print('Mapped production API compilation passed')
