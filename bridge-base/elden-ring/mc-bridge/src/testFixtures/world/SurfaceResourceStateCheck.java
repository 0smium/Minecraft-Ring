package dev.ermc.bridge.world;

import dev.ermc.bridge.CoordMap;
import dev.ermc.bridge.world.SurfaceResourcePlan.Candidate;
import dev.ermc.bridge.world.SurfaceResourcePlan.Kind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Actual production site evidence and SavedData/NBT; no world or game process is started. */
public final class SurfaceResourceStateCheck {
    private static int checks;
    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
    private static Candidate candidate(int x, int z, Kind kind) {
        return new Candidate(91, x, z, kind, 17);
    }
    private static void fill(SurfaceResourceSites sites, Candidate c, double height, long now) {
        int r = SurfaceResourcePlan.radius(c.kind());
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            sites.observe(c.x()+dx, c.z()+dz, height, true, now);
        }
    }
    private static SurfaceResourceSites stable(Candidate c, double height, long now) {
        SurfaceResourceSites sites = new SurfaceResourceSites();
        fill(sites, c, height, now);
        sites.observe(c.x(), c.z(), height, true, now+500);
        return sites;
    }
    private static void state() throws Exception {
        SurfaceResourceData data = new SurfaceResourceData();
        check(data.enabled() && data.compatible() && data.count() == 0, "fresh world defaults on");
        check(!data.isDirty(), "fresh data unexpectedly dirty");
        data.enabled(false);
        check(!data.enabled() && data.isDirty(), "toggle must dirty the world record");
        data.setDirty(false);
        data.enabled(false);
        check(!data.isDirty(), "identical toggle rewrites unchanged state");

        for (long id : new long[]{0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 300}) data.generated(id);
        check(data.count() == 5 && data.isDirty(), "receipts lose signed or zero candidate ids");
        data.setDirty(false);
        data.generated(300);
        check(data.count() == 5 && !data.isDirty(), "generated receipt must be idempotent");
        CompoundTag saved = data.save(new CompoundTag(), null);
        check(saved.getInt("version") == 2 && !saved.getBoolean("enabled"), "version/toggle not serialized");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(saved, new DataOutputStream(bytes));
        CompoundTag reread = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        SurfaceResourceData loaded = SurfaceResourceData.load(reread);
        check(!loaded.enabled() && loaded.compatible() && loaded.count() == 5 && !loaded.isDirty(), "NBT reload changed state");
        for (long id : new long[]{0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 300}) {
            check(loaded.contains(id), "harvested site receipt disappeared on reload; would regenerate");
        }
        loaded.enabled(true);
        SurfaceResourceData enabled = SurfaceResourceData.load(loaded.save(new CompoundTag(), null));
        check(enabled.enabled() && enabled.count() == 5 && enabled.contains(300), "re-enabling loses harvested-site receipts");

        CompoundTag duplicated = saved.copy();
        duplicated.putLongArray("generated", new long[]{44,44,44,45});
        check(SurfaceResourceData.load(duplicated).count() == 2, "duplicate serialized receipts counted twice");
        for (int version : new int[]{0,3,Integer.MAX_VALUE}) {
            CompoundTag unknown = saved.copy();
            unknown.putInt("version", version);
            unknown.putBoolean("enabled", true);
            SurfaceResourceData incompatible = SurfaceResourceData.load(unknown);
            check(!incompatible.enabled() && !incompatible.compatible(), "unknown format enables generation");
            incompatible.enabled(true);
            check(!incompatible.enabled(), "toggle bypasses unknown-format lock");
            boolean refused = false;
            try { incompatible.save(new CompoundTag(), null); }
            catch (IllegalStateException expected) { refused = true; }
            check(refused, "unknown format silently overwritten");
        }
        for (String field : new String[]{"version","enabled","generated"}) {
            CompoundTag missing = saved.copy();
            missing.remove(field);
            SurfaceResourceData invalid = SurfaceResourceData.load(missing);
            check(!invalid.compatible() && !invalid.enabled(), "missing " + field + " creates an empty generation ledger");
            CompoundTag wrongType = saved.copy();
            wrongType.putString(field,"1");
            invalid = SurfaceResourceData.load(wrongType);
            check(!invalid.compatible() && !invalid.enabled(), "string " + field + " accepted as valid ledger");
        }
        CompoundTag wrongNumericType = saved.copy();
        wrongNumericType.putByte("version",(byte)1);
        check(!SurfaceResourceData.load(wrongNumericType).compatible(), "byte version silently treated as int version");
        wrongNumericType = saved.copy();
        wrongNumericType.putInt("enabled",1);
        check(!SurfaceResourceData.load(wrongNumericType).compatible(), "int enabled silently treated as boolean byte");
        wrongNumericType = saved.copy();
        wrongNumericType.putIntArray("generated",new int[]{44,45});
        check(!SurfaceResourceData.load(wrongNumericType).compatible(), "int-array ledger silently loses long receipts");
        check(!SurfaceResourceData.load(new CompoundTag()).enabled(), "empty existing NBT treated as new world data");
    }

    private static SurfaceResourceExclusion.Scope scope(int zone, int region, double ax) {
        return new SurfaceResourceExclusion.Scope(zone, region, ax, 12, 34, 1, true);
    }

    private static void exclusions() throws Exception {
        var identity = scope(60, 3, 56);
        var map = new CoordMap.Mapping(56, 12, 34, 1, true, false, 60, 3);
        check(SurfaceResourceExclusion.Scope.from(map).equals(identity), "mapping identity lost fields");
        boolean refused = false;
        try { SurfaceResourceExclusion.Scope.from(new CoordMap.Mapping(56,12,34,1,true,true,60,3)); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "provisional map accepted for saved exclusions");
        for (int[] bounds : new int[][]{{0,0,0}, {0,0,257}, {30_000_000,0,1}, {-30_000_000,0,1},
                {0,30_000_000,1}, {0,-30_000_000,1}, {Integer.MIN_VALUE,Integer.MAX_VALUE,256}}) {
            refused = false;
            try { new SurfaceResourceExclusion(1,identity,false,bounds[0],bounds[1],bounds[2],"bad"); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused,"out-of-bounds circle accepted");
        }
        var boundary = new SurfaceResourceExclusion(1,identity,false,29_999_999,-29_999_999,1,"boundary");
        check(boundary.overlaps(identity,29_999_999,-29_999_999,2),"valid world boundary excluded from policy");
        for (double scale : new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            refused = false;
            try { new SurfaceResourceExclusion.Scope(60,3,0,0,0,scale,true); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused,"invalid mapping scale accepted");
        }

        // v1 contains only original fields; upgrade retains off switch and harvested site receipts.
        CompoundTag v1 = new CompoundTag();
        v1.putInt("version", 1); v1.putBoolean("enabled", false); v1.putLongArray("generated", new long[]{91, -3});
        for (byte flag : new byte[]{2,-1}) {
            CompoundTag corruptV1 = v1.copy(); corruptV1.putByte("enabled",flag);
            invalidLedger(corruptV1,"invalid v1 enabled flag " + flag);
        }
        var data = SurfaceResourceData.load(v1);
        check(data.compatible() && !data.enabled() && data.count() == 2 && data.exclusions().isEmpty(), "v1 migration lost state");
        var a = data.exclude(identity, false, 0, 0, 10, "赐福门口");
        check(a.id() == 1 && data.isDirty(), "first rule id or dirty state missing");
        check(data.contains(91) && !data.enabled(), "adding exclusion changes v1 receipts or toggle");
        check(data.excluded(identity, candidate(0,0,Kind.OAK)), "circle center accepted for generation");
        check(data.excluded(identity, candidate(12,0,Kind.OAK)), "tree canopy overlapping circle accepted");
        check(!data.excluded(identity, candidate(13,0,Kind.OAK)), "disjoint canopy excluded");
        check(data.excluded(identity, candidate(9,9,Kind.OAK)), "diagonal canopy corner intersection ignored");
        check(!data.excluded(identity, candidate(10,10,Kind.OAK)), "circle incorrectly tested as bounding square");
        check(data.excluded(identity, candidate(-12,0,Kind.OAK)), "negative-coordinate canopy overlap ignored");
        check(!data.excluded(identity, candidate(-13,0,Kind.OAK)), "negative disjoint canopy excluded");
        for (var other : new SurfaceResourceExclusion.Scope[]{scope(61,3,56), scope(60,4,56), scope(60,3,57),
                new SurfaceResourceExclusion.Scope(60,3,56,13,34,1,true),
                new SurfaceResourceExclusion.Scope(60,3,56,12,35,1,true),
                new SurfaceResourceExclusion.Scope(60,3,56,12,34,2,true),
                new SurfaceResourceExclusion.Scope(60,3,56,12,34,1,false)}) {
            check(!data.excluded(other,candidate(0,0,Kind.OAK)), "exclusion leaked to different map/anchor transform");
        }
        check(data.exclusionCount(identity) == 1 && data.exclusionCount(scope(61,3,56)) == 0, "current-map count incorrect");
        var b = data.exclude(identity, false, 2, 0, 10, "重叠");
        var c = data.exclude(scope(61,3,56), true, 0, 0, 0, "整区");
        check(data.excluded(c.scope(), candidate(-10000,10000,Kind.OAK)), "whole-zone rule only excluded local point");
        check(!data.excluded(scope(62,3,56), candidate(-10000,10000,Kind.OAK)), "whole-zone rule leaked to other zone");
        check(data.removeExclusion(a.id()) && data.excluded(identity,candidate(0,0,Kind.OAK)), "removing overlap removes other rule");
        check(data.removeExclusion(b.id()) && !data.excluded(identity,candidate(0,0,Kind.OAK)), "removal does not restore eligibility");
        check(data.contains(91) && data.contains(-3) && data.count() == 2, "removing rule clears harvest ledger");
        data.setDirty(false);
        check(!data.removeExclusion(a.id()) && !data.isDirty(), "unknown deletion changes data");
        var d = data.exclude(identity, false, -100, -200, 256, "最大半径");
        check(d.id() == 4, "removed ids reused and can target a different rule");
        check(data.excluded(identity,candidate(-356,-200,Kind.ROCK)), "large signed-coordinate circle boundary lost");

        CompoundTag saved = data.save(new CompoundTag(), null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(saved, new DataOutputStream(bytes));
        var loaded = SurfaceResourceData.load(NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        check(loaded.compatible() && !loaded.enabled() && loaded.count() == 2 && loaded.exclusions().equals(data.exclusions()),
            "v2 binary NBT reload changed rules, receipts, or enabled state");
        check(loaded.exclude(identity,false,100,100,1,"次序").id() == 5, "rule id sequence lost across save");

        // Migration and policies never participate in deterministic candidate selection.
        var before = SurfaceResourcePlan.candidates(123,60,3,0,0,21);
        loaded.enabled(true); loaded.removeExclusion(d.id());
        check(before.equals(SurfaceResourcePlan.candidates(123,60,3,0,0,21)), "rules changed seeded candidates");
        check(loaded.contains(91), "remove/re-enable regenerates previously harvested candidate");

        for (String field : new String[]{"nextExclusionId","exclusions"}) {
            CompoundTag bad = saved.copy(); bad.remove(field); invalidLedger(bad, "missing " + field);
            bad = saved.copy(); bad.putString(field,"wrong"); invalidLedger(bad, "wrong type " + field);
        }
        CompoundTag bad = saved.copy();
        ListTag wrongList = new ListTag(); wrongList.add(StringTag.valueOf("not a rule")); bad.put("exclusions",wrongList);
        invalidLedger(bad, "wrong list element type");
        bad = saved.copy(); bad.putInt("nextExclusionId",3); invalidLedger(bad,"sequence collision");
        bad = saved.copy(); bad.putByte("enabled",(byte)2); invalidLedger(bad,"bad enabled flag");
        bad = saved.copy();
        ListTag duplicated = bad.getList("exclusions",Tag.TAG_COMPOUND);
        duplicated.add(duplicated.getCompound(0).copy()); invalidLedger(bad,"duplicate rule id");
        for (String field : new String[]{"id","zone","region","ax","ay","az","unitsPerMeter","flipZ","wholeZone","x","z","radius","name"}) {
            bad = saved.copy(); bad.getList("exclusions",Tag.TAG_COMPOUND).getCompound(0).remove(field);
            invalidLedger(bad,"missing rule " + field);
            bad = saved.copy(); bad.getList("exclusions",Tag.TAG_COMPOUND).getCompound(0).putString(field,"wrong");
            if (!field.equals("name")) invalidLedger(bad,"wrong rule type " + field);
        }
        bad = saved.copy(); bad.getList("exclusions",Tag.TAG_COMPOUND).getCompound(1).putInt("radius",257);
        invalidLedger(bad,"radius overflow");
        bad = saved.copy(); bad.getList("exclusions",Tag.TAG_COMPOUND).getCompound(1).putInt("radius",0);
        invalidLedger(bad,"zero radius");
        bad = saved.copy(); bad.getList("exclusions",Tag.TAG_COMPOUND).getCompound(0).putDouble("ax",Double.NaN);
        invalidLedger(bad,"nonfinite anchor");
        bad = saved.copy(); bad.getList("exclusions",Tag.TAG_COMPOUND).getCompound(0).putByte("flipZ",(byte)2);
        invalidLedger(bad,"invalid orientation flag");
        bad = saved.copy(); bad.getList("exclusions",Tag.TAG_COMPOUND).getCompound(0).putString("name","x".repeat(49));
        invalidLedger(bad,"oversized name");
        bad = saved.copy(); bad.getList("exclusions",Tag.TAG_COMPOUND).getCompound(0).putString("name","x\ny");
        invalidLedger(bad,"control character name");
        var full = new SurfaceResourceData();
        for (int i=0;i<128;i++) full.exclude(identity,false,i,0,1,"r"+i);
        refused = false;
        try { full.exclude(identity,false,200,0,1,"too many"); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused && full.exclusions().size() == 128, "unbounded exclusion list");
        bad = full.save(new CompoundTag(),null);
        ListTag oversized = bad.getList("exclusions",Tag.TAG_COMPOUND);
        oversized.add(oversized.getCompound(0).copy());
        check(!SurfaceResourceData.load(bad).compatible(),"oversized persisted exclusion list accepted");
    }

    private static void invalidLedger(CompoundTag bad, String why) {
        var rejected = SurfaceResourceData.load(bad);
        check(!rejected.compatible() && !rejected.enabled(), why + " permits generation");
        check(rejected.contains(91) && rejected.contains(-3), why + " discarded harvest receipts");
        rejected.enabled(true); rejected.generated(100);
        check(!rejected.enabled() && !rejected.contains(100) && !rejected.isDirty(), why + " mutated corrupt ledger");
        boolean refused = false;
        try { rejected.save(new CompoundTag(),null); } catch (IllegalStateException expected) { refused = true; }
        check(refused, why + " overwrote original ledger");
    }
    private static void sites() {
        Candidate oak = candidate(-17,-33,Kind.OAK);
        SurfaceResourceSites sites = new SurfaceResourceSites();
        check(sites.baseY(oak,1000) == null, "unknown terrain accepted");
        fill(sites,oak,64,1000);
        check(sites.baseY(oak,1600) == null, "one old sample counted as two observations");
        sites.observe(oak.x(),oak.z(),64,true,1499);
        check(sites.baseY(oak,1499) == null, "surface accepted before 500 ms stability");
        check(Integer.valueOf(63).equals(sites.baseY(oak,1500)), "stable negative-coordinate surface rejected");

        sites.forget(oak.x()+2,oak.z()+2);
        check(sites.baseY(oak,1500) == null, "unknown canopy corner accepted");
        sites.observe(oak.x()+2,oak.z()+2,64,true,1500);
        check(Integer.valueOf(63).equals(sites.baseY(oak,1500)), "new footprint evidence cannot repair pending site");
        sites.observe(oak.x()+2,oak.z()+2,63.8,true,1600);
        check(Integer.valueOf(63).equals(sites.baseY(oak,1600)), "small supported slope rejected");
        sites.observe(oak.x()+2,oak.z()+2,63.7,true,1700);
        check(sites.baseY(oak,1700) == null, "large footprint slope accepted");
        sites.observe(oak.x()+2,oak.z()+2,64.1,true,1800);
        check(sites.baseY(oak,1800) == null, "ground above resource base accepted");

        sites = stable(oak,64,0);
        check(Integer.valueOf(63).equals(sites.baseY(oak,60000)), "exact freshness boundary rejected");
        check(sites.baseY(oak,60001) == null, "stale canopy footprint accepted");
        sites.observe(oak.x(),oak.z(),64,true,60001);
        check(sites.baseY(oak,60001) == null, "fresh center resurrects stale footprint");

        sites = stable(oak,64,1000);
        sites.observe(oak.x(),oak.z(),64,false,1600);
        check(sites.baseY(oak,1600) == null, "non-native/low-hit/hovering ineligible sample retained old evidence");
        sites.observe(oak.x(),oak.z(),64,true,1700);
        check(sites.baseY(oak,1700) == null, "ineligible sample did not restart stability interval");
        sites.observe(oak.x(),oak.z(),64,true,2200);
        check(Integer.valueOf(63).equals(sites.baseY(oak,2200)), "eligible observations cannot recover");
        sites.forget(oak.x(),oak.z());
        check(sites.baseY(oak,2200) == null, "obstacle forget left generation eligible");

        sites = stable(oak,64,1000);
        fill(sites,oak,65,1700);
        check(sites.baseY(oak,1800) == null, "new surface height inherits old stability interval");
        sites.observe(oak.x(),oak.z(),65,true,2200);
        check(Integer.valueOf(64).equals(sites.baseY(oak,2200)), "new surface height cannot restabilize");

        for (double invalid : new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            sites = stable(oak,64,1000);
            sites.observe(oak.x(),oak.z(),invalid,true,1600);
            check(sites.baseY(oak,1600) == null, "nonfinite native sample accepted");
        }
        for (Kind kind : Kind.values()) {
            Candidate c = candidate(-100,-200,kind);
            sites = stable(c,64.5,1000);
            check(sites.baseY(c,1500) == null, "half-height surface would receive floating/raised support");
            sites = stable(c,64.75,1000);
            check(Integer.valueOf(64).equals(sites.baseY(c,1500)), "safe small foundation lift rejected");
        }

        sites = stable(oak,64,1000);
        check(sites.baseY(oak,900) == null, "clock reset accepted future observations");
        fill(sites,oak,64,900);
        check(sites.baseY(oak,1400) == null, "clock reset retained two-observation state");
        sites.observe(oak.x(),oak.z(),64,true,1400);
        check(Integer.valueOf(63).equals(sites.baseY(oak,1400)), "clock reset cannot recover with fresh evidence");
        sites.clear();
        check(sites.size() == 0 && sites.baseY(oak,1400) == null, "world reset retained evidence");

        sites = stable(oak,64,1000);
        for (int x = 100; x < 100+8192; x++) sites.observe(x,500,64,true,1600);
        check(sites.size() == 8192, "evidence cache is not bounded at 8192");
        check(sites.baseY(oak,1600) == null, "old sample not evicted from bounded cache");
        sites.clear();
        sites.observe(-1,0,64,true,1);
        sites.observe(0,-1,64,true,1);
        sites.observe(Integer.MIN_VALUE,Integer.MAX_VALUE,64,true,1);
        sites.observe(Integer.MAX_VALUE,Integer.MIN_VALUE,64,true,1);
        check(sites.size() == 4, "signed-coordinate key collision");
        sites.forget(0,-1);
        check(sites.size() == 3, "forget removed wrong signed-coordinate entry");

        // A continuously refreshed column must eventually stabilize too. Needing a quiet
        // >=200 ms gap would permanently starve generation at common ray sample rates.
        sites = new SurfaceResourceSites();
        fill(sites,oak,64,0);
        for (long t = 50; t <= 600; t += 50) sites.observe(oak.x(),oak.z(),64,true,t);
        check(Integer.valueOf(63).equals(sites.baseY(oak,600)), "continuous 50 ms observations never stabilize");
    }
    public static void main(String[] args) throws Exception {
        state();
        exclusions();
        sites();
        System.out.println("Surface resource state: " + checks + " assertions passed (real SavedData, binary NBT, live-evidence policy).");
    }
}
