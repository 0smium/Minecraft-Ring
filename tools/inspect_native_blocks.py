"""Read the optional MBCL snapshot/status. No process attachment, writes or game startup."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import struct
import time

ROOT=Path(__file__).resolve().parents[1]
MAGIC=0x4C43424D

def stable_read(stream, offset, size):
    for _ in range(3):
        stream.seek(offset);data=stream.read(size)
        if len(data)!=size:return None
        before=struct.unpack_from('<I',data)[0]
        stream.seek(offset);after=stream.read(4)
        if len(after)==4 and not before&1 and before==struct.unpack('<I',after)[0]:return data
    return None

def inspect(path):
    now=int(time.time()*1000)
    result={'read_at':datetime.now(timezone.utc).isoformat(),'path':str(path),'read_only':True}
    with path.open('rb') as stream:
        request=stable_read(stream,0x400000,0x3060)
        status=stable_read(stream,0x410000,0x78)
    if request and struct.unpack_from('<II',request,4)==(MAGIC,1):
        flags=struct.unpack_from('<I',request,12)[0]
        revision,sampled,host_start,mc_start=struct.unpack_from('<QQQQ',request,16)
        host_pid,generation,life,zone,mc_pid,count=struct.unpack_from('<IIIIII',request,48)
        result['request']={'flags':flags,'revision':revision,'age_ms':now-sampled,'fresh':0<=now-sampled<=750,
            'host_pid':host_pid,'host_start':host_start,'generation':generation,'life':life,'zone':f'{zone:08X}',
            'mc_pid':mc_pid,'mc_start':mc_start,'count':count,'center':struct.unpack_from('<fff',request,72),
            'world_epoch':struct.unpack_from('<Q',request,88)[0]}
        if count<=512:
            result['request']['boxes_sha256']=hashlib.sha256(request[96:96+count*24]).hexdigest()
            result['request']['first_boxes']=[struct.unpack_from('<ffffff',request,96+i*24) for i in range(min(count,3))]
    else:result['request']={'available':False}
    if status and struct.unpack_from('<II',status,4)==(MAGIC,1):
        state=struct.unpack_from('<I',status,12)[0]
        sampled,revision=struct.unpack_from('<QQ',status,48)
        owned,desired,pending,quarantined,created,removed,error,flags=struct.unpack_from('<IIIIIIII',status,64)
        result['native']={'state':state,'meaning':{0:'disabled',1:'waiting/suspended',2:'converging',3:'broadphase complete',4:'blocked'}.get(state,'unknown'),
            'age_ms':now-sampled,'fresh':0<=now-sampled<=2000,'revision':revision,'owned':owned,'desired':desired,
            'pending':pending,'quarantined_bodies':quarantined,'created_this_tick':created,'removed_this_tick':removed,
            'error_id':f'{error:08X}','flags':flags,'world_epoch':struct.unpack_from('<Q',status,112)[0]}
    else:result['native']={'available':False}
    result['limit']='Broadphase completion is not proof of player/enemy/projectile/melee collision. See native blocks lines in er-bridge.log for error text.'
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mapping',type=Path,default=ROOT/'runtime/bridge.shm')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    output=json.dumps(inspect(args.mapping),ensure_ascii=False,indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(output+'\n',encoding='utf8')
    print(output)

if __name__=='__main__':main()
