## Raw Block Furnace

Smelt **raw ore blocks** straight into their **metal blocks** — vanilla raw blocks, modded raw blocks, and AllTheCompressed compressed raw blocks.

### Files
| File | Minecraft | Loader | Java |
|---|---|---|---|
| `raw_block_furnace-<ver>-mc1.21.1-neoforge.jar` | 1.21.1 | NeoForge 21.1.248+ | 21 |
| `raw_block_furnace-<ver>-mc1.20.1-forge.jar` | 1.20.1 | Forge 47.x | 17 |

### Recipes
- **Vanilla raw blocks**: Block of Raw Iron/Copper/Gold → Block of Iron/Copper/Gold, 200 ticks, 6.3 / 6.3 / 9.0 XP.
- **AllTheCompressed** (optional): `allthecompressed:raw_<metal>_block_<n>x` → `allthecompressed:<metal>_block_<n>x`, 1x–9x, 200 ticks, XP × 9 × level.
- **Any mod's raw blocks**: detected at runtime by name convention (`raw_<m>_block`, `block_raw_<m>`, `raw_block_<m>`, or the `c:storage_blocks/raw_<m>` tag).

All recipes are plain vanilla `minecraft:smelting`, so furnaces work and JEI / REI / EMI index them.

### Install
Drop the jar for your Minecraft version into `mods/`. No config, no client-side code.
