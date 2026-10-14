"""Exercise terrain prediction and refresh without launching the games."""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JDK = next((ROOT / '.tools/java').iterdir())
OUT = ROOT / 'build/terrain-prefetch-test'
OUT.mkdir(parents=True, exist_ok=True)
source = OUT / 'TerrainPrefetchTest.java'
source.write_text(r'''
import dev.ermc.bridge.TerrainPrefetch;

public class TerrainPrefetchTest {
    static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
    static void close(double a, double b, String description) {
        require(Math.abs(a - b) < 1e-8, description + ": " + a + " vs " + b);
    }
    public static void main(String[] args) {
        long now = 10000;
        var distant = new TerrainPrefetch.Sample(100, 0, 0, now);
        require(TerrainPrefetch.needsSample(distant, 20, 0, 18, 100, 0, now, true),
            "a wall sampled before host streaming must be rechecked ahead of arrival");
        var nearby = new TerrainPrefetch.Sample(100, 18, 0, now);
        require(!TerrainPrefetch.needsSample(nearby, 20, 0, 18, 100, 0, now, true), "fresh evidence is reused");
        require(TerrainPrefetch.needsSample(nearby, 20, 0, 18, 100, 0, now + 2000, true),
            "nearby streamed geometry is refreshed without a height change");
        require(TerrainPrefetch.needsSample(nearby, 20, 0, 18, 100.5, 0, now, true),
            "stairs recheck wall probes before the previous two-metre height threshold");
        double[] idle = TerrainPrefetch.ahead(0, 0, 0, 0, 1, 0);
        close(idle[0], 12, "standing player prefetches the viewed corridor");
        double[] sprint = TerrainPrefetch.ahead(0, 0, 0, 5.6, -1, 0);
        close(sprint[0], 0, "movement takes priority over a sideways camera");
        close(sprint[1], 16.8, "sprint prefetches three seconds ahead");
        require(TerrainPrefetch.ahead(0, 0, 100, 0, 1, 0)[0] <= 24, "teleport does not request an unbounded area");

        System.out.println("PASS: predictive travel, streamed/stale geometry and stairs");
    }
}
''', encoding='utf-8')
production = ROOT / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainPrefetch.java'
subprocess.run([str(JDK/'bin/javac.exe'), '-d', str(OUT), str(production), str(source)], check=True)
subprocess.run([str(JDK/'bin/java.exe'), '-cp', str(OUT), 'TerrainPrefetchTest'], check=True)

# Exercise the actual queue and scheduling functions, with platform-only doubles.
# This catches resource requests changing the terrain cache or bypassing ray budgets.
main = production.parent
manager = (main / 'TerrainManager.java').read_text(encoding='utf-8')

def method(signature):
    start = manager.index(signature)
    opening = manager.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (manager[end] == '{') - (manager[end] == '}')
        end += 1
    return manager[start:end]

constants = []
for name in ('RAY_ABOVE', 'RAY_BELOW', 'RAY_HIGH', 'PROBE_HEIGHTS', 'RAYS_PER_COLUMN', 'BATCH'):
    found = re.search(r'private static final [^;\n]+\b' + name + r'\s*=\s*[^;]+;', manager)
    if not found:
        raise RuntimeError('Cannot find production sampling constant ' + name)
    constants.append(found.group())
queue_test = OUT / 'ResourceSurfaceSchedulerTest.java'
queue_test.write_text(r'''
import dev.ermc.bridge.TerrainPrefetch;
import dev.ermc.bridge.TerrainClearance;
import java.util.*;
public class ResourceSurfaceSchedulerTest {
 static final class LongQueue extends LinkedHashSet<Long> {
  public long removeFirstLong(){long value=iterator().next();remove(value);return value;}
 }
 static final LongQueue RESOURCE_REFRESH=new LongQueue();
 static final Set<Long> MOVING_COLUMNS=new HashSet<>(),BATCH_KEYS=new HashSet<>();
 static final Map<Long,Column> COLUMNS=new HashMap<>();
 static final List<PendingColumn> PENDING_COLUMNS=new ArrayList<>();
 record Column(TerrainPrefetch.Sample sample) {}
 record PendingColumn(long key,double y,double fromX,double fromZ) {}
 static final class CoordMap {record Mapping() {}}
 static final class Mth {static int floor(double n){return (int)Math.floor(n);}}
 static final class ChunkPos {static long asLong(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}}
 static int emitted;
 static void putRay(CoordMap.Mapping map,int index,double x,double y,double z,double ex,double ey,double ez){
  check(index>=0&&index<BATCH,"ordinary shared mailbox ray capacity");emitted++;
 }
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 static void batch(){BATCH_KEYS.clear();PENDING_COLUMNS.clear();emitted=0;}
 static void fillCache(){
  COLUMNS.clear();
  long now=System.currentTimeMillis();
  for(int x=-26;x<=26;x++)for(int z=-26;z<=26;z++)
   COLUMNS.put(ChunkPos.asLong(x,z),new Column(new TerrainPrefetch.Sample(100,0,0,now)));
 }
''' + '\n'.join(constants) + '\n' + '\n'.join(method(signature) for signature in (
    'public static void clearResourceSurfaceRequests(',
    'public static void refreshResourceSurface(',
    'private static int sampleAround(')) + r'''
 public static void main(String[] args){
  var map=new CoordMap.Mapping();fillCache();batch();
  var before=new HashMap<>(COLUMNS);
  check(sampleAround(map,0,100,0,24,0,false,0,0)==0,"fresh distant collision cache is normally reused");
  refreshResourceSurface(20,0,2);
  check(RESOURCE_REFRESH.size()==25,"whole 5x5 crown footprint requested");
  refreshResourceSurface(20,0,2);
  check(RESOURCE_REFRESH.size()==25,"duplicate request cannot grow queue");
  check(COLUMNS.equals(before),"requesting evidence leaves all collision records intact");
  batch();
  int rays=sampleAround(map,0,100,0,24,0,false,0,0);
  check(rays==25*RAYS_PER_COLUMN&&emitted==rays,"all crown columns outside near range use normal ray layouts");
  check(PENDING_COLUMNS.size()==25&&RESOURCE_REFRESH.isEmpty(),"one bounded batch consumes whole footprint");
  for(var column:PENDING_COLUMNS){
   int x=(int)(column.key()>>32),z=(int)column.key();
   check(x>=18&&x<=22&&z>=-2&&z<=2,"no unrelated cached columns refreshed");
  }
  check(COLUMNS.equals(before),"preparing fresh evidence never invalidates or rewrites collision");
  refreshResourceSurface(20,0,2);
  check(sampleAround(map,0,100,0,24,rays,false,0,0)==rays,"same-batch duplicate keys never issue more rays");
  check(RESOURCE_REFRESH.size()==25,"deferred duplicate request remains available for next batch");
  batch();MOVING_COLUMNS.add(ChunkPos.asLong(20,0));
  check(sampleAround(map,0,100,0,24,0,false,0,0)==24*RAYS_PER_COLUMN,"moving support owns its column");
  check(RESOURCE_REFRESH.size()==1,"moving support request waits without modifying that column");
  MOVING_COLUMNS.clear();clearResourceSurfaceRequests();
  refreshResourceSurface(1,1,-1);refreshResourceSurface(1,1,3);
  check(RESOURCE_REFRESH.isEmpty(),"invalid request radii cannot exceed footprint limit");
  for(int i=0;i<128;i++)refreshResourceSurface(1000+i,1000,0);
  refreshResourceSurface(7,0,2);
  check(RESOURCE_REFRESH.size()==128,"fast travel queue remains bounded");
  for(int x=5;x<=9;x++)for(int z=-2;z<=2;z++)
   check(RESOURCE_REFRESH.contains(ChunkPos.asLong(x,z)),"new local crown survives obsolete distant requests");
  check(!RESOURCE_REFRESH.contains(ChunkPos.asLong(1000,1000)),"FIFO evicts obsolete oldest request");
  clearResourceSurfaceRequests();
  for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)refreshResourceSurface(x,z,0);
  check(RESOURCE_REFRESH.size()==121,"enough pending requests to fill native mailbox");
  batch();rays=sampleAround(map,0,100,0,24,0,false,0,0);
  check(rays==(BATCH/RAYS_PER_COLUMN)*RAYS_PER_COLUMN&&emitted==rays,"shared 2048 ray budget unchanged");
  check(RESOURCE_REFRESH.size()==121-BATCH/RAYS_PER_COLUMN,"unsubmitted columns remain queued");
  check(COLUMNS.equals(before),"budget exhaustion preserves all preexisting collision");
  batch();
  check(sampleAround(map,0,100,0,24,BATCH-RAYS_PER_COLUMN+1,false,0,0)==BATCH-RAYS_PER_COLUMN+1,
   "partial remaining ray capacity cannot split a column");
  check(emitted==0,"no ray written beyond remaining complete-column budget");
  clearResourceSurfaceRequests();check(RESOURCE_REFRESH.isEmpty(),"lifecycle reset can discard pending evidence");
  System.out.println("PASS: production resource surface queue, full crown refresh, deduplication, FIFO eviction, moving support and shared ray budget");
 }
}
''', encoding='utf-8')
subprocess.run([str(JDK/'bin/javac.exe'), '-d', str(OUT), str(production),
                str(main/'TerrainClearance.java'), str(queue_test)], check=True)
subprocess.run([str(JDK/'bin/java.exe'), '-cp', str(OUT), 'ResourceSurfaceSchedulerTest'], check=True)
