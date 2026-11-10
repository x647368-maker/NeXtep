#!/usr/bin/env python3
"""Prepare a signed, verified release bundle. Never uploads or publishes anything."""
import argparse,hashlib,json,pathlib,re,shutil,struct,subprocess,zipfile
CERT='b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd'
def manifest(apk):
 with zipfile.ZipFile(apk) as z:data=z.read('AndroidManifest.xml')
 u16=lambda at:struct.unpack_from('<H',data,at)[0];u32=lambda at:struct.unpack_from('<I',data,at)[0]
 strings=[];attrs={};offset=u16(2)
 while offset<len(data):
  kind,header,size=struct.unpack_from('<HHI',data,offset);assert size>=header>=8
  if kind==1:
   count=u32(offset+8);utf8=bool(u32(offset+16)&256);start=offset+u32(offset+20)
   def length(at,wide):
    value=u16(at) if wide else data[at];step=2 if wide else 1;flag=32768 if wide else 128
    if value&flag:return ((value&(flag-1))<<(16 if wide else 8))+(u16(at+step) if wide else data[at+step]),at+step*2
    return value,at+step
   for i in range(count):
    pos=start+u32(offset+header+4*i);n,pos=length(pos,not utf8)
    if utf8:n,pos=length(pos,False)
    strings.append(data[pos:pos+n*(1 if utf8 else 2)].decode('utf-8' if utf8 else 'utf-16le'))
  elif kind==0x102:
   ext=offset+16;name=strings[u32(ext+4)]
   if name=='manifest':
    for i in range(u16(ext+12)):
     at=ext+u16(ext+8)+i*u16(ext+10);key=strings[u32(at+4)];raw=u32(at+8);typ=data[at+15];value=u32(at+16)
     attrs[key]=strings[raw] if raw!=0xffffffff else strings[value] if typ==3 else value
  offset+=size
 return attrs
def release_channel(name):
 if not re.fullmatch(r'\\d+\\.\\d+\\.\\d+(?:-dev)?',name):
  raise ValueError('Unsupported versionName for signed update metadata: '+repr(name))
 return 'dev' if name.endswith('-dev') else 'stable'

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--apk',required=True);p.add_argument('--apksigner',required=True);p.add_argument('--notes',required=True);p.add_argument('--out',required=True);a=p.parse_args()
 apk=pathlib.Path(a.apk);out=pathlib.Path(a.out);out.mkdir(parents=True,exist_ok=True)
 identity=manifest(apk)
 assert identity['package']=='io.github.x647368maker.atomicworkbench'
 assert identity['versionCode']>0
 channel=release_channel(identity['versionName'])
 signature=subprocess.run(['java','-jar',a.apksigner,'verify','--min-sdk-version','35','--print-certs',str(apk)],check=True,capture_output=True,text=True).stdout
 assert 'Signer #1 certificate SHA-256 digest: '+CERT in signature
 assert re.fullmatch(r'[A-Za-z0-9._-]+\.apk',apk.name)
 sha=hashlib.sha256(apk.read_bytes()).hexdigest();notes=pathlib.Path(a.notes).read_text()
 meta=dict(schemaVersion=1,applicationId=identity['package'],versionName=identity['versionName'],versionCode=identity['versionCode'],channel=channel,apkAsset=apk.name,sha256=sha,size=apk.stat().st_size,certificateSha256=CERT,releaseNotes=notes,deviceNotes='主要适配 PMS110 / ColorOS 17；本次实机验收尚未完成，其他机型未验证')
 if apk.resolve()!=(out/apk.name).resolve():shutil.copyfile(apk,out/apk.name)
 (out/'atomic-update.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2))
 (out/(apk.name+'.sha256')).write_text(sha+'  '+apk.name+'\n')
 print(json.dumps({k:meta[k] for k in ['applicationId','versionName','versionCode','channel','apkAsset','sha256','size']}))
