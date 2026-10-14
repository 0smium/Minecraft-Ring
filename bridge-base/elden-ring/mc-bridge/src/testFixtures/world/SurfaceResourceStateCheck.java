package dev.ermc.bridge.world;

import dev.ermc.bridge.world.SurfaceResourcePlan.Candidate;
import dev.ermc.bridge.world.SurfaceResourcePlan.Kind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

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
        check(saved.getInt("version") == 1 && !saved.getBoolean("enabled"), "version/toggle not serialized");
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
        for (int version : new int[]{0,2,Integer.MAX_VALUE}) {
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
        sites();
        System.out.println("Surface resource state: " + checks + " assertions passed (real SavedData, binary NBT, live-evidence policy).");
    }
}
