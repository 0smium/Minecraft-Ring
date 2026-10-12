package dev.ermc.bridge.link;

/** A successfully read optional host-effect sample; validity and age are checked by its consumer. */
public final class HostEffectState {
	public int flags;
	public long frame;
	public int hostLife;
	public int poisonEffectId;

	public boolean has(int flag) {
		return (flags & flag) != 0;
	}
}
