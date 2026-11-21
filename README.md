# Raw Block Furnace

把**粗矿块**直接熔炼成对应的**金属块**的轻量模组 —— 原版粗矿块、其它模组的粗矿块、以及压缩方块模组
（AllTheCompressed）的压缩粗矿块都支持。

Smelt **raw ore blocks** straight into their **metal blocks** — for vanilla raw blocks, modded raw
blocks, and AllTheCompressed compressed raw blocks.

| | 加载器 / Loader | Minecraft | 依赖 / Requires |
|---|---|---|---|
| [1.20.1-forge](1.20.1-forge/) | Forge 47.x | 1.20.1 | Java 17 |
| [1.21.1-neoforge](1.21.1-neoforge/) | NeoForge 21.1.248+ | 1.21.1 | Java 21 |

## 做了什么 / What it does

原版 1.20.1 / 1.21.1 里，粗矿块**没有任何熔炉配方**（只有「粗矿物品 → 锭」）。本模组补上：

- `minecraft:raw_iron_block` → `minecraft:iron_block`
- `minecraft:raw_copper_block` → `minecraft:copper_block`
- `minecraft:raw_gold_block` → `minecraft:gold_block`
- 装了 [AllTheCompressed](https://www.curseforge.com/minecraft/mc-mods/allthecompressed)（modid `allthecompressed`）时：
  `allthecompressed:raw_<metal>_block_<n>x` → `allthecompressed:<metal>_block_<n>x`（n = 1..9）
- **其它模组的粗矿块**（Mekanism / Immersive Engineering / AllTheOres / Thermal …）由运行时逻辑按注册名自动识别配对。

全部使用**原版 `minecraft:smelting` 配方**，所以熔炉能烧、JEI / REI / EMI 与配方书都能识别。

## 数值 / Numbers

| 配方 | 时间 | 经验 |
|---|---|---|
| 原版粗铁 / 粗铜块 | 200 tick | 6.3 |
| 原版粗金块 | 200 tick | 9.0 |
| 压缩粗矿块 1x … 9x | 200 tick | 单块经验 × 9 × n（铁/铜 1x = 56.7，9x = 510.3；金 1x = 81，9x = 729） |

## 运行时如何兼容其它模组 / How modded support works

服务端在 `OnDatapackSyncEvent`（早于配方包下发，登录与 `/reload` 都覆盖）里扫注册表，按规则配对：

- `<ns>:raw_<m>_block` → `<ns>:<m>_block` / `<ns>:block_<m>`（原版 / AllTheOres / Thermal 风格）
- `<ns>:block_raw_<m>` → `<ns>:block_<m>`（Mekanism 风格）
- `<ns>:raw_block_<m>` → `<ns>:block_<m>`（Immersive Engineering 风格）
- 兜底：`#c:storage_blocks/raw_<m>` ↔ `#c:storage_blocks/<m>` 标签配对

幂等、按 id 去重、跳过已被任何熔炉配方覆盖的输入，不与其它模组的配方打架。

## 构建 / Build

```sh
cd 1.21.1-neoforge && ./gradlew build     # -> build/libs/raw_block_furnace-1.0.0.jar
cd 1.20.1-forge   && ./gradlew build      # -> build/libs/raw_block_furnace-1.0.0.jar
```

## License

MIT
