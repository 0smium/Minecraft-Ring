"""Exercise production policy lease with a fake monotonic clock; no Minecraft is started."""
from pathlib import Path
import subprocess
import tempfile
from test_b01_preparation import java_path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'bridge-base/elden-ring/mc-bridge/character-bridge/src/client/java/dev/ermc/bridge/client/PolicyHeartbeatLease.java'
CHECK = r'''
import dev.ermc.bridge.client.PolicyHeartbeatLease;
public class PolicyLeaseCheck {
 static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static long s(double n){return (long)(n*1_000_000_000L);}
 public static void main(String[] args){
  var lease=new PolicyHeartbeatLease();check(!lease.expired(s(20),false),"no invented authority before first packet");
  lease.receive(s(20));check(!lease.expired(s(20.8),false),"normal heartbeat interval");
  check(!lease.expired(s(21),true),"local pause starts");check(!lease.expired(s(100),true),"long local pause not silence");
  check(!lease.expired(s(102),false),"resume first tick precedes packet without revoking");
  check(!lease.expired(s(103),false),"server gets its heartbeat interval after resume");
  lease.receive(s(103));check(!lease.expired(s(105.9),false),"new receipt renews lease");
  check(lease.expired(s(106),false),"real running silence still expires at three seconds");
  check(lease.expired(s(107),true)==false,"paused expired lease does not renew itself");
  check(lease.expired(s(110),false),"resume cannot resurrect previously expired authority");
  lease.reset();check(!lease.expired(s(500),false),"disconnect clears clock");
  lease.receive(s(500));check(lease.expired(s(504),false),"remote/nonpaused menu does not freeze clock");
  lease.reset();lease.receive(s(600));lease.expired(s(600.1),true);lease.receive(s(610));
  check(!lease.expired(s(610.5),true),"real packet accepted while paused");
  check(!lease.expired(s(620),false),"receipt during pause resumes safely");
  check(lease.expired(s(623),false),"new real silence still expires");
  lease.reset();lease.receive(s(700));lease.expired(s(700.5),false);lease.expired(s(701),true);lease.expired(s(705),false);
  lease.expired(s(705.5),false);lease.expired(s(706),true);lease.expired(s(710),false);
  check(lease.expired(s(712),false),"repeated pauses preserve accumulated running silence");
  System.out.println("PASS: "+checks+" fake-clock production policy lease assertions; no GUI/game/network exercised");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='erbridge-lease-') as temp:
    work = Path(temp)
    probe = work / 'PolicyLeaseCheck.java'
    probe.write_text(CHECK, encoding='utf-8')
    java = java_path(None)
    subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-d', str(work), str(SOURCE), str(probe)], check=True)
    subprocess.run([java, '-cp', str(work), 'PolicyLeaseCheck'], check=True)
