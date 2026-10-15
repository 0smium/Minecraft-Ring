"""Compile and exercise the actual deterministic resource planner without either game."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "runtime/experiments/surface-resource-plan-check"
SOURCE = ROOT / "bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/world/SurfaceResourcePlan.java"
HARNESS = r'''
import dev.ermc.bridge.world.SurfaceResourcePlan;
import dev.ermc.bridge.world.SurfaceResourcePlan.*;
import java.util.*;

public class SurfaceResourcePlanCheck {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private record Point(int x, int y, int z) {
        Point plus(int x, int y, int z) { return new Point(this.x+x, this.y+y, this.z+z); }
    }
    private static final int[][] SIDES = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static boolean log(Material m) { return m == Material.OAK_LOG || m == Material.BIRCH_LOG; }
    private static boolean leaves(Material m) { return m == Material.OAK_LEAVES || m == Material.BIRCH_LEAVES; }
    private static boolean plant(Material m) {
        return m == Material.DANDELION || m == Material.POPPY || m == Material.SHORT_GRASS
                || m == Material.SWEET_BERRY_BUSH;
    }
    private static Set<Long> ids(List<Candidate> candidates) {
        var result = new HashSet<Long>();
        for (Candidate c : candidates) check(result.add(c.id()), "duplicate candidate id");
        return result;
    }
    private static void layout(Kind kind, long seed) {
        Candidate c = new Candidate(17, -150, -201, kind, seed);
        var placements = SurfaceResourcePlan.layout(c, 64);
        check(placements.equals(SurfaceResourcePlan.layout(c, -64)), "height changed resource layout");
        check(!placements.isEmpty() && placements.size() <= 64, "per-resource block budget");
        var blocks = new HashMap<Point, Material>();
        int radius = SurfaceResourcePlan.radius(kind), top = SurfaceResourcePlan.maxHeight(kind);
        for (Placement p : placements) {
            check(Math.abs(p.dx()) <= radius && Math.abs(p.dz()) <= radius
                    && p.dy() >= 0 && p.dy() <= top, "out-of-bounds layout");
            check(blocks.put(new Point(p.dx(),p.dy(),p.dz()),p.material()) == null, "overlapping layout blocks");
        }
        check(blocks.containsKey(new Point(0,0,0)), "missing anchor support");
        for (var entry : blocks.entrySet()) {
            Point p = entry.getKey();
            Material m = entry.getValue();
            if (plant(m)) check(blocks.get(p.plus(0,-1,0)) == Material.GRASS_BLOCK, "unsupported plant");
            if (log(m)) check(p.y() == 1 ? blocks.get(p.plus(0,-1,0)) == Material.DIRT
                    : log(blocks.get(p.plus(0,-1,0))), "broken trunk");
            if (kind == Kind.ROCK && p.y() > 0) check(blocks.containsKey(p.plus(0,-1,0)), "floating rock");
        }
        if (kind == Kind.OAK || kind == Kind.BIRCH) {
            var distances = new HashMap<Point, Integer>();
            var queue = new ArrayDeque<Point>();
            blocks.forEach((p,m) -> { if (log(m)) { distances.put(p,0); queue.add(p); } });
            check(queue.size() >= 4 && queue.size() <= 5, "unexpected trunk height");
            while (!queue.isEmpty()) {
                Point p = queue.remove();
                for (int[] side : SIDES) {
                    Point next = p.plus(side[0],side[1],side[2]);
                    if (leaves(blocks.get(next)) && !distances.containsKey(next)) {
                        distances.put(next, distances.get(p)+1);
                        queue.add(next);
                    }
                }
            }
            for (var entry : blocks.entrySet()) if (leaves(entry.getValue())) {
                check(distances.getOrDefault(entry.getKey(), 99) <= 6, "leaf would decay while tree is intact");
            }
        }
    }
    private static void query(long seed, int zone, int region, int x, int z, int radius) {
        var first = SurfaceResourcePlan.candidates(seed,zone,region,x,z,radius);
        check(first.equals(SurfaceResourcePlan.candidates(seed,zone,region,x,z,radius)), "non-deterministic query");
        ids(first);
        check(first.size() < 1100, "query candidate budget");
        var occupied = new HashSet<String>();
        for (Candidate c : first) {
            long dx = (long)c.x()-x, dz = (long)c.z()-z;
            check(dx*dx+dz*dz <= (long)radius*radius, "candidate outside search radius");
            for (Placement p : SurfaceResourcePlan.layout(c,0)) {
                String at = ((long)c.x()+p.dx()) + ":" + p.dy() + ":" + ((long)c.z()+p.dz());
                check(occupied.add(at), "neighboring candidates overlap");
            }
        }
    }
    public static void main(String[] args) {
        for (Kind kind : Kind.values()) for (long seed = -40; seed <= 40; seed++) layout(kind, seed);
        for (int x : new int[]{-129,-17,-16,-1,0,1,15,16,127}) query(100,60,42,x,-x,48);
        query(0,60,42,-10000,-10000,128);
        query(Long.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,128);
        query(Long.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,128);

        var large = SurfaceResourcePlan.candidates(100,60,42,0,0,128);
        var largeIds = ids(large);
        for (int x : new int[]{-48,-16,0,16,48}) {
            var small = SurfaceResourcePlan.candidates(100,60,42,x,-x,32);
            check(largeIds.containsAll(ids(small)), "exploration order/radius changed identity");
            for (Candidate c : small) check(large.contains(c), "overlap changed candidate seed or position");
        }
        for (int[] other : new int[][]{{61,42},{60,43},{42,60},{-60,42}}) {
            var otherIds = ids(SurfaceResourcePlan.candidates(100,other[0],other[1],0,0,128));
            otherIds.retainAll(largeIds);
            check(otherIds.isEmpty(), "zone/region namespaces overlap");
        }
        var otherSeed = ids(SurfaceResourcePlan.candidates(101,60,42,0,0,128));
        otherSeed.retainAll(largeIds);
        check(otherSeed.isEmpty(), "world seed namespace overlaps");
        var kinds = EnumSet.noneOf(Kind.class);
        boolean coal = false;
        for (Candidate c : large) {
            kinds.add(c.kind());
            coal |= SurfaceResourcePlan.layout(c,0).stream().anyMatch(p -> p.material() == Material.COAL_ORE);
        }
        check(kinds.equals(EnumSet.allOf(Kind.class)) && coal, "basic resource coverage");
        Candidate exact = large.getFirst();
        check(SurfaceResourcePlan.candidates(100,60,42,exact.x(),exact.z(),0).contains(exact), "radius zero boundary");
        for (int bad : new int[]{-1,129,Integer.MAX_VALUE}) {
            boolean rejected = false;
            try { SurfaceResourcePlan.candidates(0,0,0,0,0,bad); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected,"unbounded query accepted");
        }
        System.out.println("Surface resource planner: " + checks + " assertions passed; "
                + large.size() + " candidates in radius 128; all 6 resource kinds covered.");
    }
}
'''


def cursor_harness():
    """Run production loop prefixes; world placement is replaced with an accepted-id sink."""
    runtime = (SOURCE.parent / "SurfaceResources.java").read_text(encoding="utf-8")

    def block(start):
        opening = runtime.index("{", start)
        depth, end = 1, opening + 1
        while depth:
            depth += (runtime[end] == "{") - (runtime[end] == "}")
            end += 1
        return runtime[start:end]

    refresh = block(runtime.index("if (now - lastRefresh >= 1000)"))
    beginning = runtime.index("int checked = 0;", runtime.index("long deadline ="))
    prefix = runtime[beginning:runtime.index("if (base < level.getMinBuildHeight()", beginning)]
    distance = block(runtime.index("private static double distanceSq("))
    return r'''
import dev.ermc.bridge.world.SurfaceResourcePlan;
import dev.ermc.bridge.world.SurfaceResourcePlan.*;
import java.util.*;
public class SurfaceResourceCursorCheck {
 static int cursor,refreshCursor;
 static long lastRefresh;
 static String status;
 static final Data data=new Data();
 static final Owner owner=new Owner();
 static final Sites SITES=new Sites();
 static final Set<Long> accepted=new HashSet<>();
 static final class Data {boolean contains(long id){return false;}}
 static final class Owner {double getX(){return 0;}double getZ(){return 0;}}
 static final class Sites {
  final Set<Long> ready=new HashSet<>();
  Integer baseY(Candidate candidate,long now){return ready.contains(candidate.id())?100:null;}
 }
 static final class TerrainManager {
  static final Set<Integer> requested=new HashSet<>();
  static void refreshResourceSurface(int x,int z,int radius){requested.add(x-100);}
 }
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 static List<Candidate> scene(int count){
  var result=new ArrayList<Candidate>();
  for(int i=0;i<count;i++)result.add(new Candidate(i,100+i,0,Kind.OAK,i));
  return result;
 }
 static void reset(){cursor=refreshCursor=0;lastRefresh=0;accepted.clear();SITES.ready.clear();TerrainManager.requested.clear();}
 static void refresh(List<Candidate> candidates,long now){
''' + refresh + r'''
 }
 static void generate(List<Candidate> candidates,long deadline){
  long now=0;
''' + prefix + r'''
   // Stop at the production placement boundary: this test only observes candidates
   // whose evidence and cursor budget admitted them, not simulated block writes.
   accepted.add(candidate.id());return;
  }
 }
''' + distance + r'''
 public static void main(String[] args){
  reset();var seventeen=scene(17);SITES.ready.add(16L);
  generate(seventeen,Long.MAX_VALUE);
  check(accepted.isEmpty()&&cursor==16,"budget must leave the seventeenth candidate for the next tick");
  generate(seventeen,Long.MAX_VALUE);
  check(accepted.contains(16L),"the only usable seventeenth candidate must not starve behind sixteen unsuitable ones");
  reset();cursor=9;
  generate(scene(20),Long.MIN_VALUE);
  check(cursor==9&&accepted.isEmpty(),"expired time budget must not consume the next candidate");
  // A stationary 20-site scene previously refreshed only a gcd subset when the
  // refresh cadence reused the 16-candidate placement cursor. Include skipped
  // refresh intervals as well as the ordinary two half-second placement ticks.
  for(int ticksPerRefresh:new int[]{0,1,2,4}){
   reset();var twenty=scene(20);
   for(int second=1;second<=20;second++){
    for(int tick=0;tick<ticksPerRefresh;tick++)generate(twenty,Long.MAX_VALUE);
    refresh(twenty,second*2000L);
   }
   check(TerrainManager.requested.size()==20,"all twenty missing sites must receive refreshes regardless of placement cadence "+ticksPerRefresh);
  }
  reset();refresh(List.of(),1000);generate(List.of(),Long.MAX_VALUE);
  check(cursor==0&&refreshCursor==0,"empty scene must not advance either cursor");
  System.out.println("Surface resource production cursors: 17-site placement fairness, 20-site refresh coverage and expired-budget preservation passed.");
 }
}
'''


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    classes = OUT / "classes"
    classes.mkdir(exist_ok=True)
    harness = OUT / "SurfaceResourcePlanCheck.java"
    harness.write_text(HARNESS, encoding="utf-8")
    cursors = OUT / "SurfaceResourceCursorCheck.java"
    cursors.write_text(cursor_harness(), encoding="utf-8")
    java = next((ROOT / ".tools/java").glob("*/bin/java.exe"))
    for name, args in (
        ("compile", ["-m", "jdk.compiler/com.sun.tools.javac.Main", "-proc:none", "-encoding", "UTF-8",
                     "-d", str(classes), str(SOURCE), str(harness), str(cursors)]),
        ("check", ["-cp", str(classes), "SurfaceResourcePlanCheck"]),
        ("cursors", ["-cp", str(classes), "SurfaceResourceCursorCheck"]),
    ):
        result = subprocess.run([str(java), *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
        output = result.stdout + result.stderr
        (OUT / f"{name}.log").write_text(output, encoding="utf-8")
        if result.returncode:
            raise RuntimeError(f"{name} failed:\n{output}")
        if output:
            print(output, end="")


if __name__ == "__main__":
    main()
