"""Exercise the production precision-flight CODEC with actual MC 1.21.1 buffers.

Uses an existing named Loom classpath; compiles current production sources ahead
of cached classes. No Gradle, network connection, doubles or game startup.
Identity/sequence authorization belongs to the runtime lease tests, not the CODEC.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from test_b01_payload import arguments_file

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'bridge-base/elden-ring/mc-bridge/character-bridge/src/main/java/dev/ermc/bridge/gameplay/runtime/PrecisionFlightPayload.java'

JAVA = r'''
import dev.ermc.bridge.gameplay.runtime.PrecisionFlightPayload;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
public final class PrecisionFlightPayloadCheck {
    static final UUID OWNER = new UUID(0x123456789abcdef0L, 0xfedcba9876543210L);
    static int roundtrips, rejected;
    interface Action { void run(); }
    static void check(boolean result, String label) { if (!result) throw new AssertionError(label); }
    static void reject(Action action, String label) {
        try { action.run(); } catch (RuntimeException expected) { rejected++; return; }
        throw new AssertionError("Accepted " + label);
    }
    static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }
    static void roundtrip(PrecisionFlightPayload payload) {
        var b = buffer();
        try {
            PrecisionFlightPayload.CODEC.encode(b, payload);
            check(b.readableBytes() == 37, "bounded 37-byte payload");
            check(b.readUUID().equals(payload.owner()) && b.readLong() == payload.sequence()
                && b.readLong() == payload.hostStart() && b.readInt() == payload.hostLife()
                && b.readBoolean() == payload.active(), "wire field order and width");
            b.readerIndex(0);
            check(PrecisionFlightPayload.CODEC.decode(b).equals(payload) && !b.isReadable(), "exact roundtrip");
            roundtrips++;
        } finally { b.release(); }
    }
    public static void main(String[] args) {
        for (boolean active : new boolean[] { false, true }) {
            roundtrip(new PrecisionFlightPayload(OWNER, 1, 1000, 1, active));
            roundtrip(new PrecisionFlightPayload(OWNER, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, active));
        }
        // Inactive release can be sent while native identity is absent; these fields remain opaque wire values.
        roundtrip(new PrecisionFlightPayload(OWNER, 2, 0, 0, false));
        roundtrip(new PrecisionFlightPayload(OWNER, 3, 1001, Integer.MIN_VALUE, true));
        reject(() -> new PrecisionFlightPayload(null, 1, 1000, 1, true), "null owner");
        for (long bad : new long[] {0, -1, Long.MIN_VALUE}) {
            reject(() -> new PrecisionFlightPayload(OWNER, bad, 1000, 1, true), "constructor sequence");
            var b = buffer();
            try {
                PrecisionFlightPayload.CODEC.encode(b, new PrecisionFlightPayload(OWNER, 1, 1000, 1, true));
                b.setLong(16, bad);
                reject(() -> PrecisionFlightPayload.CODEC.decode(b), "wire sequence");
            } finally { b.release(); }
        }
        for (int prefix = 0; prefix < 37; prefix++) {
            var b = buffer();
            try {
                PrecisionFlightPayload.CODEC.encode(b, new PrecisionFlightPayload(OWNER, 1, 1000, 1, true));
                b.writerIndex(prefix);
                reject(() -> PrecisionFlightPayload.CODEC.decode(b), "truncated prefix");
            } finally { b.release(); }
        }
        var b = buffer();
        try {
            var start = new PrecisionFlightPayload(OWNER, 9, 1000, 1, true);
            var stop = new PrecisionFlightPayload(OWNER, 10, 1000, 1, false);
            PrecisionFlightPayload.CODEC.encode(b, start); PrecisionFlightPayload.CODEC.encode(b, stop);
            check(PrecisionFlightPayload.CODEC.decode(b).equals(start) && b.readableBytes() == 37
                && PrecisionFlightPayload.CODEC.decode(b).equals(stop) && !b.isReadable(), "decode consumes exactly one payload");
        } finally { b.release(); }
        check(PrecisionFlightPayload.TYPE.id().toString().equals("erbridge:precision_flight_v1"), "versioned channel");
        System.out.println("PASS: actual precision_flight_v1 CODEC; " + roundtrips + " roundtrips, " + rejected
            + " malformed cases, all 37 truncated prefixes, bounded fields and concatenated frame boundaries; real MC buffers, no games");
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--java-home', type=Path)
    args = parser.parse_args()
    classpath = args.classpath_file.read_text(encoding='utf-8-sig').strip()
    java_home = args.java_home or next((ROOT / '.tools/java').glob('*/bin/java.exe')).parent.parent
    suffix = '.exe' if os.name == 'nt' else ''
    with tempfile.TemporaryDirectory(prefix='ermc-flight-wire-') as directory:
        output = Path(directory)
        fixture = output / 'PrecisionFlightPayloadCheck.java'
        fixture.write_text(JAVA, encoding='utf-8')
        compile_args = output / 'compile.args'
        arguments_file(compile_args, ['-encoding', 'UTF-8', '-cp', classpath, '-d', output, SOURCE, fixture])
        subprocess.run([str(java_home / 'bin' / f'javac{suffix}'), '@' + str(compile_args)], cwd=ROOT, check=True)
        run_args = output / 'run.args'
        arguments_file(run_args, ['-cp', str(output) + os.pathsep + classpath, 'PrecisionFlightPayloadCheck'])
        subprocess.run([str(java_home / 'bin' / f'java{suffix}'), '@' + str(run_args)], cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
