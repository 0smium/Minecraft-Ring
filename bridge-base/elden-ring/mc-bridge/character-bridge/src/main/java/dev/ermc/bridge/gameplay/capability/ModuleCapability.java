package dev.ermc.bridge.gameplay.capability;

import dev.ermc.bridge.api.CapabilitySnapshot;
import dev.ermc.bridge.api.CapabilitySnapshot.*;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog.*;
import java.util.*;

/** Combine independently learned routes for one LB module; each route keeps its own cost and grant. */
public final class ModuleCapability {
    public record Resolved(Map<String, Setting> settings, Map<String, NumericRange> limits, Set<Ability> branches) {
        public Resolved { settings=Map.copyOf(settings); limits=Map.copyOf(limits); branches=Set.copyOf(branches); }
    }
    private ModuleCapability() {}
    public static Grant validGrant(Entry entry, CapabilitySnapshot snapshot) {
        if (snapshot == null) return null;
        Grant grant=snapshot.abilities().get(entry.id());
        if (grant == null || !grant.unlocked() || grant.rank()>entry.ranks().size()) return null;
        Rank rank=entry.ranks().get(grant.rank()-1);
        if (!rank.modes().equals(grant.allowedModes()) || !rank.limits().keySet().equals(grant.numericLimits().keySet())) return null;
        for (var limit:grant.numericLimits().entrySet()) {
            NumericRange maximum=rank.limits().get(limit.getKey());
            if(limit.getValue().minimum()<maximum.minimum() || limit.getValue().maximum()>maximum.maximum()) return null;
        }
        return grant;
    }
    public static Resolved resolve(Entry base, CapabilitySnapshot snapshot) {
        Grant baseGrant=validGrant(base,snapshot);
        Rank initial=base.ranks().get(baseGrant==null?0:baseGrant.rank()-1);
        Map<String,Setting> settings=new LinkedHashMap<>();initial.settings().values().forEach(s->settings.put(s.path(),s));
        Map<String,NumericRange> limits=new HashMap<>(baseGrant==null?initial.limits():baseGrant.numericLimits());
        Set<Ability> branches=new HashSet<>();
        if(baseGrant!=null) for(Entry branch:AbilityCatalog.instance().entriesForModule(base.module())) {
            if(!base.id().equals(branch.branchOf())) continue;
            Grant grant=validGrant(branch,snapshot);if(grant==null)continue;
            Rank rank=branch.ranks().get(grant.rank()-1);
            for(Setting next:rank.settings().values()) {
                Setting previous=settings.get(next.path());
                if(next.path().equals("Mode") && previous!=null) {
                    Set<String> modes=new HashSet<>(previous.type().equals("choice_set")?previous.allowedChoices():Set.of(previous.stringValue()));
                    modes.addAll(next.type().equals("choice_set")?next.allowedChoices():Set.of(next.stringValue()));
                    settings.put(next.path(),new Setting(modes.size()>1?"choice_set":"choice","Mode",null,
                            previous.stringValue(),null,modes.size()>1?modes:Set.of()));
                } else if(previous==null) settings.put(next.path(),next);
                else throw new IllegalStateException("Overlapping route setting: "+next.path());
            }
            limits.putAll(grant.numericLimits());branches.add(branch.id());
        }
        return new Resolved(settings,limits,branches);
    }
}
