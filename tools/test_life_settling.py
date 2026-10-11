"""Exercise the real native load/respawn settling gate without a running game."""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
GAME = 'bridge-base/elden-ring/er-bridge/src/game.cpp'
OUT = ROOT / 'build/life-settling-test'
OUT.mkdir(parents=True, exist_ok=True)


def function(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


source = (ROOT / GAME).read_text(encoding='utf-8')
constants = '\n'.join(re.search(r'constexpr size_t ' + name + r'\s*=\s*[^;]+;', source)[0]
                      for name in ('kPhysStandingOnGround', 'kFallTimer'))
setup = r'''
#include <cstdint>
#include <cstring>
#include <cmath>
#include <cstdio>
#include <cstdlib>
'''+constants+r'''
struct Player {uint8_t* phys; float position[3]; uint32_t zone;};
struct {float offset[3];} g_frame;
uint32_t g_settleTicks=0, g_stillTicks=0, g_settleMin=0, g_stillMin=0, g_settleZone=0;
bool g_settleNeedsLanding=false;
float g_settlePos[3], g_settleOff[3];
uint64_t g_lifeTraceUntil=0;
constexpr int LIFE_SETTLING=1;
alignas(float) uint8_t phys[0x200], fall[0x20];
bool fallAvailable=true;
uint32_t stable_pos(const Player& p,float* out) {memcpy(out,p.position,12);return p.zone;}
// A validated, owner-matching fall object, or unavailable. Validation belongs
// to player_fall; this harness tests settled's response to its returned pointer.
uint8_t* player_fall(const Player&) {return fallAvailable ? fall : nullptr;}
void set_life(int,const char*) {}
uint64_t now_ms() {return 1000;}
void trace_life_state(const Player&,bool) {}
void check(bool value,const char* message) {
    if(!value) {fprintf(stderr,"FAIL: %s (tick %u, still %u)\n",message,g_settleTicks,g_stillTicks);exit(1);}
}
void timer(float value) {memcpy(fall+kFallTimer,&value,sizeof(value));}
void reset_inputs() {
    memset(phys,0,sizeof(phys));memset(fall,0,sizeof(fall));
    memset(g_frame.offset,0,sizeof(g_frame.offset));fallAvailable=true;
}
'''
start = function(source, 'static void start_settling(')
settled = function(source, 'static bool settled(')
negative = r'''
    Player p={phys,{403.25f,-144.96f,-103.18f},0x0c010000};
    reset_inputs();
    start_settling(p,90,45,"loaded",true);
    for(int i=0;i<300;i++)
        check(!settled(p),"stable position with ground=0 must not finish loading");
'''
tests = negative+r'''
    check(g_stillTicks==0,"airborne ticks do not count towards landing stability");
    reset_inputs();phys[kPhysStandingOnGround]=1;
    start_settling(p,90,45,"respawned",true);
    for(int i=0;i<300;i++) {
        timer(4.0f+(i%9));
        check(!settled(p),"ground=1 with fall timer 4-12 must not finish respawning");
    }
    check(g_stillTicks==0,"nonzero fall timer does not count towards landing stability");
    timer(0);
    for(int i=0;i<44;i++)
        check(!settled(p),"landing needs all 45 consecutive stable ticks");
    check(settled(p),"timer=0 and ground=1 finish after exactly 45 stable ticks");
    start_settling(p,90,45,"respawned",true);
    phys[kPhysStandingOnGround]=0;
    for(int i=0;i<90;i++) check(!settled(p),"airborne waiting fulfils only the minimum elapsed ticks");
    phys[kPhysStandingOnGround]=1;
    for(int i=0;i<20;i++) check(!settled(p),"an incomplete landing remains blocked");
    phys[kPhysStandingOnGround]=0;
    check(!settled(p) && g_stillTicks==0,"one lost ground frame resets landing stability");
    phys[kPhysStandingOnGround]=1;
    for(int i=0;i<44;i++)
        check(!settled(p),"lost ground requires a fresh 45 consecutive stable ticks");
    check(settled(p),"settling resumes after another full 45 stable ticks");
    puts("PASS: 300 stationary airborne ticks, nonzero fall timer, 45 landing ticks and interrupted landing");

    reset_inputs();timer(12);fallAvailable=false;
    start_settling(p,10,0,"character present");
    for(int i=0;i<9;i++) check(!settled(p),"hot reload still waits its 10 minimum ticks");
    check(settled(p),"hot reload does not require ground or a fall object");
    start_settling(p,30,15,"the game moved the Tarnished");
    for(int i=0;i<29;i++) check(!settled(p),"warp still waits its 30 minimum ticks");
    check(settled(p),"warp uses 30/15 settling without requiring ground or a fall object");
    puts("PASS: hot reload 10/0 and warp 30/15 retain their original handoff rules");

    reset_inputs();phys[kPhysStandingOnGround]=1;fallAvailable=false;
    start_settling(p,90,45,"loaded",true);
    for(int i=0;i<300;i++)
        check(!settled(p),"unavailable fall object must not finish loading");
    check(g_stillTicks==0,"unavailable fall object cannot accumulate landing stability");
    puts("PASS: unavailable validated fall object keeps loading blocked");
'''
cxx = next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe'))


def compile_run(name, implementation, body):
    cpp = OUT / (name + '.cpp')
    exe = OUT / (name + '.exe')
    cpp.write_text(setup + start + '\n' + implementation + '\nint main() {\n' + body + '\n}\n', encoding='utf-8')
    subprocess.run([str(cxx), '-std=c++17', '-static', str(cpp), '-o', str(exe)], check=True)
    return subprocess.run([str(exe)], capture_output=True, text=True)


result = compile_run('candidate', settled, tests)
print(result.stdout, end='', flush=True)
if result.returncode:
    print(result.stderr, end='', flush=True)
    raise SystemExit(result.returncode)

baseline_source = subprocess.check_output(['git', 'show', '481d11d:' + GAME], cwd=ROOT, text=True, encoding='utf-8')
baseline = compile_run('baseline', function(baseline_source, 'static bool settled('), negative)
if baseline.returncode != 1 or 'stable position with ground=0' not in baseline.stderr:
    print(baseline.stdout + baseline.stderr, end='', flush=True)
    raise RuntimeError('The old settled() did not reproduce the new airborne negative case')
print('PASS: real 481d11d settled() fails the airborne regression at tick 90 (expected)', flush=True)
print('EXPECTED BASELINE FAILURE: ' + baseline.stderr.strip().removeprefix('FAIL: '), flush=True)
