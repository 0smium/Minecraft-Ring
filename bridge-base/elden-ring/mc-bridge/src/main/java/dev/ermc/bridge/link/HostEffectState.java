package dev.ermc.bridge.link;

/** A successfully read optional host-effect sample; validity and age are checked by its consumer. */
public final class HostEffectState {
	public int version;
	public int flags;
	public long frame;
	public int hostLife;
	public int poisonEffectId;
	public int rotEffectId, frostEffectId, sleepEffectId, madnessEffectId;
	public int frostProcs;

	public boolean has(int flag) {
		return (flags & flag) != 0;
	}
}
