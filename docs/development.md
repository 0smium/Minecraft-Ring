# Development and diagnostics

[Back to Minecraft Ring](../README.md) · [Build instructions](installation.md)

## Architecture

| Component | Responsibility |
| --- | --- |
| `bridge-base/elden-ring/er-bridge/src/proxy.cpp` | `dinput8.dll` loader; gates activation and loads the core |
| `er-bridge/src/game.cpp` | Player and camera hooks, host interactions, terrain rays and moving support |
| `er-bridge/src/compositor.cpp`, `gpu_transport.h` | D3D12 compositing, depth occlusion and GPU frame acknowledgements |
| `er-bridge/src/crash.cpp` | Fault context, thread stacks and minidumps |
| `mc-bridge/src/main/java/dev/ermc/bridge/` | Server-side terrain, collision shapes, moving platforms and shared life |
| `mc-bridge/src/main/java/dev/ermc/bridge/entity/` | Host enemy proxies and combat |
| `mc-bridge/src/client/java/dev/ermc/bridge/client/` | Camera, capture, Windows overlay, input and automatic bridge-world opening |
| `mc-bridge/character-bridge/` | Independent character UI, Horse lifecycle, effects, progression and optional Skills/LB authorization |
| `mc-bridge/src/main/java/dev/ermc/bridge/api/` | Public motion, ordered lifecycle and host/capability data contracts |
| `Launch.ps1` | Profile build/dependency preflight, host startup and Minecraft launch |

Paths abbreviated to `er-bridge/` and `mc-bridge/` above are relative to
`bridge-base/elden-ring/`.

Core can build without Character, Skills, LB or Meteor. Character is an opt-in
Gradle subproject and a separate Fabric JAR; build/launch flags and compatibility
are documented in [character modules](character-modules.md).

## Protocol and rendering

`er-bridge/include/bridge_protocol.h` and
`mc-bridge/src/main/java/dev/ermc/bridge/link/Protocol.java` mirror the shared
layout. Keep them synchronized. The bridge uses file-backed mappings under
`ERMC_DIR`; the Windows launcher sets it to the project's `runtime/` directory.
Java can also override the directory with `-Derbridge.dir=<path>`.

Actual host effects use an optional, separately versioned `HEFX` block at
`0xC00`. The native `HostEffectsReader` reads identified ER state;
the optional Character mod's `HostEffectsBridge` applies vanilla Minecraft effects on the server thread.
Unattributed HP loss is observation data rather than an effect identity or a
second damage event. HEFX v2 adds rot, frost, sleep and madness; the Java reader also accepts
poison-only v1. The mappings, experimental frost burst and rollout checks are in
[host-state mapping](host-state-mapping.md).

The optional `HINV v2` block at `0x360000` publishes the local host's
carried inventory and equipment-slot observations. Full reads run at most twice
per second; loading or owner changes invalidate old data immediately. The B02
[inventory and Minecraft horse candidate](b02-character-slice.md) includes the
original viewer, managed vanilla Horse trial and scoped debug-enemy cleanup.
The current [equipment page](equipment-v1.md) uses HINV v2 identity/capabilities
and a separate confirmed equipment mailbox at `0x370000`; v1 remains readable
but cannot authorize writes.

State and control slots use sequence counters so readers can reject an update
in progress. Terrain queries, moving-platform cells, enemy state and combat
events have their own regions. Legacy `hunter` names refer to the Tarnished.
The frame transport uses separate files and supports four layers per frame
slot, at resolutions up to 3840 × 2160.

On supported drivers, OpenGL captures are exchanged as shared GPU textures
with D3D12. The memory path remains a fallback. Minecraft captures the camera
pose alongside each frame so host rendering can use the corresponding pose.
During F8 handoff, both sides retire pending captures and publications rather
than waiting for a fresh Minecraft frame to release old buffers.

## Optional clients and ability policies

[The Meteor candidate](meteor-integration.md) adds an isolated `run-meteor`
production profile and exact-version optional mixins. `HostEntityView` shares
host validity and target rules with LB without a client compile dependency.
The official release JAR and eight injection calls have passed offline checks;
module initialization and gameplay remain outstanding.
[Ability progression](ability-progression-strategy.md) defines the proposed
server-owned limits, module modes and revocation rules. B01 now connects real
host levels and the [14-ability skill tree](growth-v1.md), with costs, staged
limits and reversible test presets. Meteor growth authorization remains a proposal.

## Terrain and moving platforms

`TerrainManager` uses the terrain generation and movement path from `ffb31b5`.
Prefetch covers 12–24 metres ahead and an eight-metre radius around the predicted
position. Nearby passage refinement uses 16x16 horizontal cells with 16 vertical
layers. Immediate footing takes priority; local refinement alternates with
broader coarse generation. The native ray
mailbox retains its 2 ms frame budget; ten-chunk preloading and continuous
16x16x16 refinement are disabled.

Minecraft resolves movement against generated block collision on both client
and server. Additional native colliders, wall projection and landing position
corrections are disabled. Saved shapes remain available during chunk loading;
unknown detail shapes retain solid support until their packets arrive. The
cached shape builder reads both saved 8x8 and 16x16 data. Passage refinement
preserves saved fine cells; lift updates retain their conservative 8x8 view. Player-built
blocks are preserved, and door invalidation rejects older pending queries.

Passage refinement discovers blocking terrain in the player's next 1.5 metres,
including ordinary floor slabs, independent of coarse obstacle classification.
It measures five floor points per 1/16-block cell and checks local headroom and
bidirectional wall diagonals. It follows descending stairs and clears verified
air up to the real ceiling within four metres of each floor, leaving step and
jump room. A 1,280-ray floor batch is followed by up to four 1,088-ray local
batches. Each result updates only its measured fine cells; cells are never
downsampled between partial batches. The existing native frame budget is
unchanged. Player-origin floor/headroom and remote visibility cannot veto
independently verified local clearance. Real low ceilings remain ceiling
surfaces instead of turning the whole column into a wall.

Verified air is cached even when its world block becomes air and has no block
entity. Coarse obstacle classification changes reuse that measured shape;
explicit door/prop invalidation removes it before new local sampling. Body
batches start with cells nearest the player, and each batch gets its own timeout
so a slow multi-batch refinement does not discard its final results.

`TerrainTravelClearance` provides an urgent lane in the same mailbox. It checks
overlapping body-sized patches for four metres along movement and for 1.5 metres
in every direction around the player. One 160-ray floor batch and one body batch
of at most 1,696 rays check support, ceilings and bidirectional wall segments.
Only verified air inside the patches is removed; support and player blocks stay.
Detailed columns yield between parts so this lane can run before arrival,
alternating with background generation. Diagnostics include wave duration and
the number of patches with verified clearance.
Steeper patches retain per-cell refinement instead of becoming raised flat
platforms; that refinement starts at already measured higher ground, allowing
upcoming slopes to be prepared while the player is still below them.

Native support tracking runs each game frame independently of terrain work.
A separate nearby platform table is sampled every 40 ms in half-metre cells
within three metres of the player. A floor qualifies by moving at the same
world point, with footprint and body-clearance checks. The Minecraft side
predicts travel, updates verified floor cells and carries grounded players;
jumping detaches the passenger while tracking continues for landing. Flight
or lost support ends the ride. Sloped-floor and edge handling remain subjects
for gameplay testing.

## Regression checks

`/weather thunder` uses native WindyRain only. The bridge adds no lightning or
thunder audio and loads no additional storm resources; this requires no DLC.

Run from the repository root after preparing the toolchains. These checks
exercise collision, movement and capture behavior without launching the games:

| Command | Coverage |
| --- | --- |
| `python tools/test_host_effects.py` | Native-to-Java HEFX v1/v2, owned vanilla-effect leases, cadence, overlap, cleanup and optional frost events; platform stubs, not a game build |
| `python tools/test_host_effects.py --vanilla` | Also tests actual MC 1.21.1 weaker/longer hidden-effect merging, ticking, nested and infinite chains; no game startup |
| `python tools/test_host_effects_mixin.py` | Built production accessor applied to the actual intermediary MC effect class; run after Fabric build |
| `python tools/test_meteor_preparation.py` | Optional Meteor 0.5.8 handlers, shared LB target rules and artifact-preflight fixtures; no real Mixin launch |
| `python tools/test_meteor_adapter.py` | Eight actual optional-client injections into the pinned Meteor release JAR; offline transformer, not module initialization |
| `python tools/test_b01_preparation.py` | [B01 preparation](b01-preparation.md): character/session isolation, persistent level entitlement and capability policies; simulated hosts and fresh-JVM restore |
| `python tools/test_local_host_routing.py --fabric-events --regression-ref b8615e1` | Real Combat/Life and Terrain recall code with platform doubles; pinned Fabric/MC JOIN order, local owner isolation and counter restarts; rejects old broadcast behavior |
| `python tools/test_test_runes.py` | Explicit rune command bounds, ownership/session checks, expiry/replay handling, real function arithmetic on private fixture memory, closed-game backup and CLI tests; no live mailbox access |
| `python tools/test_host_inventory.py` then `python tools/test_inventory_client.py` | Target-exe read signatures, actual C++ reader/feed with memory doubles, C++ publisher bytes consumed by Java; no live inventory |
| `python tools/test_debug_spawn_ownership.py` | Actual native request/recognition/cleanup code with private objects; no full-debug-set deletion or late-result adoption |
| `python tools/test_mount_vanilla.py` and `python tools/test_mount_lifecycle.py` | Real MC stack/components/codec and extracted cleanup functions with lifecycle doubles; actual riding remains unverified |
| `python tools/test_stable_terrain.py` | Active block collision path and saved 8x8/16x16 compatibility |
| `python tools/test_terrain_passages.py` | Two-stage passage refinement, descending stairs, low vaults, wall retention and door invalidation |
| `python tools/test_terrain_clearance.py` | Narrow arches, corner and one-sided walls, stale openings and floor safety |
| `python tools/test_terrain_prefetch.py` | Predictive sampling, stale geometry and stairs |
| `python tools/test_frame_switch.py` | F8 key handoff, delayed GL completion, GPU acknowledgements and resumed capture |
| `python tools/test_moving_platform.py` | Lift travel, delayed ticks, floor/ceiling aliasing, static ground and lost support |
| `python tools/test_terrain_regeneration.py` | Saved collision and detail retained across startup and chunk reloads |
| `python tools/test_platform_boarding.py` | Boarding before contact, slopes, platform edges, intact walls and departed floors |
| `python tools/test_player_facing.py` | Native player headings and both sides of a slanted doorway |
| `python tools/test_crash_recorder.py` | Repeated faults, context and minidump output, unchanged exception handling |
| `python tools/test_world_environment.py` | Weather/time commands, smooth native transitions and scripted overrides |

Build the native DLLs with `python tools/build_native.py`, and build the Fabric
mod with Gradle `build` as described in the [setup guide](installation.md#build).
Most regression scripts generate isolated harnesses under `build/`; B01 uses an
automatically cleaned temporary directory. Generated files and compiled DLLs
stay out of Git. Automated checks do not establish that every
Elden Ring area, GPU driver or gameplay transition works.

## Logs and local data

| Path | Data |
| --- | --- |
| `runtime/er-bridge.log` | Native loader, hooks, rendering and bridge log |
| `runtime/eldenring-crash.txt` | Relevant fault context, registers and thread stacks |
| `runtime/eldenring-crash.dmp` | Native minidump |
| `bridge-base/elden-ring/mc-bridge/run/logs/latest.log` | Core / LB Minecraft/Fabric log |
| `bridge-base/elden-ring/mc-bridge/run-meteor/logs/latest.log` | Isolated Meteor candidate log |
| `bridge-base/elden-ring/mc-bridge/run/saves/er-bridge/` | Local Minecraft bridge world and builds |
| `installation.json`, `backups/` | Installation record, prior mod files and Elden Ring save backups |

For a bug report, include the exact game/mod version, GPU and driver, location,
steps to reproduce and relevant logs. Describe whether it happens in creative
or survival mode, before or after F8, and with compositing enabled. Inspect a
crash dump before sharing it: it can contain process memory and local paths.

`tools/status.py` reads bridge state and checks whether its heartbeat advances.
`tools/screenshot.py` captures the visible host window and needs Pillow; it
writes `runtime/game.png`. Minecraft's own screenshot captures may contain only
the Minecraft layer when Elden Ring is compositing the final frame.

## Repository hygiene

The root `.gitignore` allows project sources, documentation and the selected
README screenshot explicitly. New top-level files require an allow rule.
Toolchains, caches, logs, crash dumps, runtime mappings, saves, installation
records, binaries, reference checkouts and unrelated upstream game modules
remain excluded.
Local reverse-engineering helpers that depend on the unpublished `erctl` module
are also excluded; published diagnostics are self-contained. Memory extracts,
game saves, local databases and credential directories must stay out of Git.

The Windows Python builder is the supported native build entry point here.
The native Makefile is retained from the cross-compilation workflow; the
original macOS/CrossOver launcher scripts are not part of this Windows package.
