package net.ccbluex.liquidbounce.erbridgefixture;
import net.ccbluex.liquidbounce.config.types.Choice;
import net.ccbluex.liquidbounce.config.types.ChoiceConfigurable;
/** Explicit lifecycle counters around the original ChoiceConfigurable implementation. */
public final class FixtureChoice extends Choice {
    public ChoiceConfigurable<?> owner;
    public int enables, disables;
    public FixtureChoice(String name){super(name);}
    @Override public ChoiceConfigurable<?> getParent(){return owner;}
    @Override public void enable(){enables++;}
    @Override public void disable(){disables++;}
}
