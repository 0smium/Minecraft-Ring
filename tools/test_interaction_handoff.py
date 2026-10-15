"""Exercise the production MC-originated menu handoff policy; never runs either game."""
from pathlib import Path
import subprocess
import tempfile
from test_b01_preparation import java_path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'bridge-base/elden-ring/mc-bridge/src/client/java/dev/ermc/bridge/client/InteractionHandoffPolicy.java'
JAVA = r'''
import dev.ermc.bridge.client.InteractionHandoffPolicy;
import static dev.ermc.bridge.client.InteractionHandoffPolicy.Decision.*;
public class HandoffCheck {
 static final InteractionHandoffPolicy.Identity ID=new InteractionHandoffPolicy.Identity(7,100,1,2,3);
 static int n; static void check(boolean x,String why){n++;if(!x)throw new AssertionError(why);}
 static InteractionHandoffPolicy armed(){var p=new InteractionHandoffPolicy();p.request(42,ID,1000);p.acknowledge(42,1);return p;}
 static InteractionHandoffPolicy hosted(){var p=armed();check(p.observe(ID,true,true,false,false,false,true,1,1100)==TO_HOST,"observed native menu after acknowledged MC action");return p;}
 public static void main(String[] args){
  var p=new InteractionHandoffPolicy();check(p.observe(ID,true,true,false,false,false,true,1,1000)==NONE,"unowned menu never switches");
  p.request(42,ID,1000);p.acknowledge(41,1);check(p.observe(ID,true,true,false,false,false,true,1,1100)==NONE,"wrong ack cannot claim menu");
  p.acknowledge(42,0);check(!p.pending(),"failed action cancels");
  p=armed();check(p.observe(ID,true,false,true,false,false,true,1,1100)==NONE,"successful door without menu does not switch");
  check(p.observe(ID,true,true,false,false,false,true,2,9001)==NONE&&!p.pending(),"unrelated late menu cannot claim expired action");
  p=hosted();check(p.observe(ID,true,true,false,true,false,true,30,600000)==NONE&&p.pending(),"long menu remains owned while observations live");
  check(p.observe(ID,true,false,false,true,false,true,31,600010)==NONE,"closed menu while standing up does not return");
  check(p.observe(ID,true,false,true,true,false,true,32,601000)==NONE,"stable return begins");
  check(p.observe(ID,true,false,true,true,false,true,32,601400)==NONE,"frozen frame cannot authorize return");
  check(p.observe(ID,true,false,true,true,false,true,33,601401)==TO_MC&&!p.pending(),"fresh stable closure returns exactly once");
  check(p.observe(ID,true,false,true,true,false,true,34,601800)==NONE,"no repeated return");
  p=hosted();p.observe(ID,true,false,true,true,false,true,2,2000);
  check(p.observe(ID,true,true,false,true,false,true,3,2400)==NONE,"reopened menu resets stable window");
  p.observe(ID,true,false,true,true,false,true,4,2500);
  check(p.observe(ID,true,false,true,true,false,true,5,2800)==NONE,"needs full new stability duration");
  check(p.observe(ID,true,false,true,true,false,true,6,2851)==TO_MC,"second stable closure");
  p=hosted();check(p.observe(ID,true,false,true,true,true,false,2,2000)==NONE&&!p.pending(),"AltTab cancels ownership without stealing focus");
  p=hosted();check(p.observe(ID,true,false,true,false,false,true,2,2000)==NONE&&!p.pending(),"manual return cancels future handoff");
  p=armed();check(p.observe(ID,true,true,false,true,false,true,2,2000)==NONE&&!p.pending(),"manual host switch is not owned");
  for(var id:new InteractionHandoffPolicy.Identity[]{new InteractionHandoffPolicy.Identity(8,100,1,2,3),new InteractionHandoffPolicy.Identity(7,101,1,2,3),new InteractionHandoffPolicy.Identity(7,100,2,2,3),new InteractionHandoffPolicy.Identity(7,100,1,3,3),new InteractionHandoffPolicy.Identity(7,100,1,2,4)}){
   p=hosted();check(p.observe(id,true,false,true,true,false,true,2,2000)==NONE&&!p.pending(),"identity/world replacement cancels");
  }
  p=hosted();check(p.observe(null,false,false,false,true,false,true,2,2000)==NONE&&!p.pending(),"unknown/dead/loading cancels");
  p=hosted();p.observe(ID,true,false,true,true,false,false,2,2000);check(p.pending(),"null focus is a gap, not another app");
  p.observe(ID,true,false,true,true,false,true,3,3000);
  check(p.observe(ID,true,false,true,true,false,true,4,3200)==NONE,"unknown focus time not counted as stable ready");
  p.cancel();check(!p.pending(),"explicit disconnect/F8 cancels");
  p=armed();check(p.observe(ID,true,true,false,false,false,true,1,999)==NONE&&!p.pending(),"clock rollback cancels");
  p.request(0,ID,1000);check(!p.pending(),"empty request cannot arm");
  System.out.println("PASS: "+n+" production handoff checks; actual menus/focus need manual validation");
 }
}
'''

with tempfile.TemporaryDirectory(prefix='ermc-handoff-') as directory:
    out = Path(directory)
    fixture = out / 'HandoffCheck.java'
    fixture.write_text(JAVA, encoding='utf8')
    java = Path(java_path(None))
    subprocess.run([str(java.with_name('javac.exe')), '-d', str(out), str(SOURCE), str(fixture)], check=True)
    subprocess.run([str(java), '-cp', str(out), 'HandoffCheck'], check=True)
