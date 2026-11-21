# Raw Block Furnace

Smelt **raw ore blocks** straight into their **metal blocks** — works with vanilla raw blocks, modded raw blocks, and compressed raw blocks.

Vanilla Minecraft has no furnace recipe for raw ore blocks (only "raw ore item → ingot"). This mod fills that gap and goes one step further: it recognises raw ore blocks from **other mods** automatically, and supports the compressed raw blocks from **AllTheCompressed**.

## Recipes

### Vanilla raw blocks (always available)

| Input | Output | Time | XP |
|---|---|---|---|
| Block of Raw Iron | Block of Iron | 200 ticks | 6.3 |
| Block of Raw Copper | Block of Copper | 200 ticks | 6.3 |
| Block of Raw Gold | Block of Gold | 200 ticks | 9.0 |

These are additive — vanilla has no such recipe, so nothing is overridden.

### Compressed raw blocks (requires AllTheCompressed)

With [AllTheCompressed](https://www.curseforge.com/minecraft/mc-mods/allthecompressed) installed, every compressed raw block smelts into the matching compressed block:

| Input | Output | Time | XP |
|---|---|---|---|
| `allthecompressed:raw_<metal>_block_1x` … `_9x` | `allthecompressed:<metal>_block_1x` … `_9x` | 200 ticks | base × 9 × level |

18 materials are covered out of the box (iron, copper, gold, tin, lead, silver, nickel, osmium, platinum, iridium, aluminum, zinc, uranium, allthemodium, vibranium, unobtainium, limonite, emberstone — whichever are actually present in your pack).

### Other mods' raw blocks (automatic)

Mekanism (`block_raw_osmium`), Immersive Engineering (`raw_block_aluminum`), AllTheOres / Thermal (`raw_tin_block`) and any mod using the `c:storage_blocks/raw_<material>` conventions are detected **at runtime** and get a matching furnace recipe automatically. No config, no datapack needed.

## Compatibility

- **Recipe type:** plain vanilla `minecraft:smelting` — so the furnace works, and JEI / REI / EMI and the recipe book can index them.
- **AllTheCompressed:** optional, never required. Without it the compressed recipes simply do not exist.
- **Other recipe mods:** existing furnace recipes are never replaced — every input that already smelts into something is skipped.
- **Client/server:** pure gameplay data + server-side logic; install on the server (and on clients for single-player). No client-side code.

## Versions

| Minecraft | Loader | Requires |
|---|---|---|
| 1.21.1 | NeoForge 21.1.248+ | Java 21 |
| 1.20.1 | Forge 47.x | Java 17 |

## Notes

- Runtime-generated recipes do not unlock entries in the furnace recipe book, but they are fully visible in JEI / EMI and usable by right-clicking them into the furnace.
- Numbers are deliberately simple: everything takes 200 ticks (like a vanilla smelt), experience scales with the compression level.
