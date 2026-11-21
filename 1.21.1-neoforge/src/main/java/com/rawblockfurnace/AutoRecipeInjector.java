package com.rawblockfurnace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import org.slf4j.Logger;

/**
 * 运行时"通配"注入：让<b>任何模组</b>的粗矿块都能在熔炉里熔成对应的金属块，压缩方块模组
 * （AllTheCompressed）的压缩粗矿块也能熔成对应的压缩成品块。
 *
 * <p>为什么不用 JSON：粗矿块与其成品块的注册名由各模组自行决定（{@code mekanism:raw_osmium_block}
 * 对应 {@code mekanism:block_osmium}，{@code alltheores:raw_tin_block} 对应
 * {@code alltheores:tin_block}…），datapack JSON 无法表达"按名字推导"，而 result 也不支持 tag。
 * 因此这里在服务端运行时按规则扫注册表，把原版 {@link SmeltingRecipe} 塞进 RecipeManager。
 *
 * <p>注入时机选 {@link OnDatapackSyncEvent}：NeoForge 把它插在"向客户端广播配方包之前"，
 * 登录与 {@code /reload} 两条路径都会经过，所以注入的配方会随 {@code ClientboundUpdateRecipesPacket}
 * 同步给客户端，JEI（在 {@code RecipesUpdatedEvent} 里取 {@code RecipeManager.getRecipes()}）能识别。
 * 反过来，{@code AddReloadListenerEvent} 不可用：{@code RecipeManager.apply()} 会整表覆盖，且监听器无顺序保证。
 */
public final class AutoRecipeInjector {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 粗矿块 / 粗矿物品块的通用命名：{@code raw_<material>_block}。 */
    private static final Pattern RAW_BLOCK = Pattern.compile("raw_([a-z0-9_]+)_block");
    /** Immersive Engineering 式命名：{@code raw_block_<material>}。 */
    private static final Pattern RAW_BLOCK_PREFIXED = Pattern.compile("raw_block_([a-z0-9_]+)");
    /** Mekanism 式命名：{@code block_raw_<material>}。 */
    private static final Pattern RAW_BLOCK_MEKANISM = Pattern.compile("block_raw_([a-z0-9_]+)");
    /** 压缩方块模组（AllTheCompressed）的命名：{@code <原方块 id>_<n>x}，n = 1..9。 */
    private static final Pattern COMPRESSED = Pattern.compile("([a-z0-9_]+)_([1-9])x");
    /** 压缩方块模组的命名空间。 */
    private static final String COMPRESSED_NS = "allthecompressed";

    /** 与仓库里静态 JSON 保持一致的数值。 */
    private static final int SMELTING_TIME = 200;
    private static final float RAW_BLOCK_XP = 6.3F;
    /**
     * 单块粗矿块的熔炼经验：默认按"9 个粗矿物品"折算（铁/铜 0.7 x 9 = 6.3）；
     * 金锭是 1.0，所以金用 9.0（与仓库里的静态 JSON 保持一致）。其它材质无法判断，统一按 6.3。
     */
    private static float rawBlockXp(String material) {
        return "gold".equals(material) ? 9.0F : RAW_BLOCK_XP;
    }
    /** 压缩级：时间仍是 200 tick（与原版粗矿块一致），但经验按 9 x n 放大（1x = 9 个粗矿块）。 */
    private static final float COMPRESSED_XP_FACTOR = 9F;

    private AutoRecipeInjector() {}

    /** 登录 / {@code /reload} 时注入（NeoForge 保证在配方包广播之前触发）。 */
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        inject(event.getPlayerList().getServer().getRecipeManager());
    }

    /** 服务端启动后也注入一次：让熔炉在第一个玩家登录之前就具备这些配方。 */
    public static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        inject(event.getServer().getRecipeManager());
    }

    private static void inject(RecipeManager recipeManager) {
        // 以现有配方为基底（getRecipes() == byName.values()），按 id 去重，保证多次触发（登录 / /reload）幂等。
        final Map<ResourceLocation, RecipeHolder<?>> merged = new LinkedHashMap<>();
        for (final RecipeHolder<?> holder : recipeManager.getRecipes()) {
            merged.put(holder.id(), holder);
        }
        final Set<Item> alreadySmeltable = collectSmeltingInputs(merged.values());

        // 压缩级优先：万一某个整合包把压缩粗矿块也打进了 c:storage_blocks/raw_<material> 标签，
        // 也要先按压缩规则（等级/倍数正确）生成，别让标签兜底先占位成普通 200 tick 的配方。
        final int compressed = addCompressedRecipes(merged, alreadySmeltable);
        final int normal = addRawBlockRecipes(merged, alreadySmeltable);

        if (normal + compressed > 0) {
            recipeManager.replaceRecipes(merged.values());
            // 熔炉侧的配方查找（RecipeManager.CachedCheck）只缓存配方 id，且每次都经
            // level.getRecipeManager() 解析，所以 replaceRecipes 后自动生效，无需额外失效动作。
            LOGGER.info("[{}] injected {} runtime smelting recipes (raw blocks: {}, compressed: {})",
                    RawBlockFurnace.MODID, normal + compressed, normal, compressed);
        } else {
            LOGGER.debug("[{}] no new runtime smelting recipes to inject", RawBlockFurnace.MODID);
        }
    }

    // ---------------------------------------------------------------- 原版风格粗矿块

    /**
     * 扫描全部已注册物品，把各模组的"粗矿块"熔炼成对应的金属块。识别两种常见命名：
     * <ul>
     *   <li>{@code <ns>:raw_<material>_block}（原版 / AllTheOres / Thermal …）→ {@code <ns>:<material>_block}</li>
     *   <li>{@code <ns>:block_raw_<material>}（Mekanism 式）→ {@code <ns>:block_<material>}</li>
     * </ul>
     * 名字推导失败时再退回 NeoForge 约定标签 {@code c:storage_blocks/raw_<material>} ↔
     * {@code c:storage_blocks/<material>} 配对（见 {@link #addTagDrivenRecipes}）。
     */
    private static int addRawBlockRecipes(Map<ResourceLocation, RecipeHolder<?>> merged, Set<Item> alreadySmeltable) {
        int added = 0;
        for (final ResourceLocation sourceId : BuiltInRegistries.ITEM.keySet()) {
            final String material = rawBlockMaterial(sourceId.getPath());
            if (material == null) {
                continue;
            }
            final Item source = BuiltInRegistries.ITEM.get(sourceId);
            if (!(source instanceof BlockItem) || alreadySmeltable.contains(source)) {
                continue;
            }
            final Item result = findCounterpartBlock(sourceId.getNamespace(), material);
            if (result == null) {
                continue;
            }
            final ResourceLocation recipeId = recipeId("raw/" + sourceId.getNamespace() + "/" + sourceId.getPath());
            if (merged.containsKey(recipeId)) {
                continue;
            }
            merged.put(recipeId, smelting(recipeId, source, result, rawBlockXp(material), SMELTING_TIME));
            alreadySmeltable.add(source);
            added++;
        }
        added += addTagDrivenRecipes(merged, alreadySmeltable);
        return added;
    }

    /** 从注册名里取出粗矿块的材质名，认不出就返回 {@code null}。 */
    private static String rawBlockMaterial(String path) {
        Matcher matcher = RAW_BLOCK.matcher(path);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        matcher = RAW_BLOCK_PREFIXED.matcher(path);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        matcher = RAW_BLOCK_MEKANISM.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** 依次尝试 {@code <material>_block}、{@code block_<material>}（同命名空间），再退回约定标签。 */
    private static Item findCounterpartBlock(String namespace, String material) {
        Item candidate = blockIn(namespace, material + "_block");
        if (candidate == null) {
            candidate = blockIn(namespace, "block_" + material);
        }
        if (candidate == null) {
            final List<Item> fromTag = tagContents(tagOf("storage_blocks/" + material));
            candidate = fromTag.stream()
                    .filter(item -> namespace.equals(BuiltInRegistries.ITEM.getKey(item).getNamespace()))
                    .findFirst()
                    .orElse(fromTag.size() == 1 ? fromTag.get(0) : null);
        }
        return candidate;
    }

    /**
     * 标签驱动兜底：遍历所有 {@code c:storage_blocks/raw_<material>} 标签，把标签里的粗矿块
     * 配对到 {@code c:storage_blocks/<material>} 里的方块（同命名空间优先）。这样命名不合套路的
     * 模组也能被覆盖。
     */
    private static int addTagDrivenRecipes(Map<ResourceLocation, RecipeHolder<?>> merged, Set<Item> alreadySmeltable) {
        int added = 0;
        final String prefix = "storage_blocks/raw_";
        for (final TagKey<Item> rawTag : BuiltInRegistries.ITEM.getTagNames().toList()) {
            final ResourceLocation rawTagId = rawTag.location();
            if (!"c".equals(rawTagId.getNamespace()) || !rawTagId.getPath().startsWith(prefix)) {
                continue;
            }
            final String material = rawTagId.getPath().substring(prefix.length());
            final List<Item> outputs = tagContents(tagOf("storage_blocks/" + material));
            if (outputs.isEmpty()) {
                continue;
            }
            for (final Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(rawTag)) {
                final Item source = holder.value();
                if (!(source instanceof BlockItem) || alreadySmeltable.contains(source)) {
                    continue;
                }
                final ResourceLocation sourceId = BuiltInRegistries.ITEM.getKey(source);
                // 压缩方块（<base>_<n>x）由压缩规则处理，这里不插手。
                if (COMPRESSED.matcher(sourceId.getPath()).matches()) {
                    continue;
                }
                final Item result = outputs.stream()
                        .filter(item -> sourceId.getNamespace().equals(
                                BuiltInRegistries.ITEM.getKey(item).getNamespace()))
                        .findFirst()
                        .orElse(outputs.size() == 1 ? outputs.get(0) : null);
                if (result == null) {
                    continue;
                }
                final ResourceLocation recipeId = recipeId("raw/" + sourceId.getNamespace() + "/" + sourceId.getPath());
                if (merged.containsKey(recipeId)) {
                    continue;
                }
                merged.put(recipeId, smelting(recipeId, source, result, rawBlockXp(material), SMELTING_TIME));
                alreadySmeltable.add(source);
                added++;
            }
        }
        return added;
    }

    private static TagKey<Item> tagOf(String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", path));
    }

    /** 取标签里的成员，只保留"方块物品且本身不是粗矿块"的候选成品。 */
    private static List<Item> tagContents(TagKey<Item> tag) {
        return BuiltInRegistries.ITEM.getTag(tag)
                .map(set -> set.stream()
                        .map(Holder::value)
                        .filter(item -> item instanceof BlockItem
                                && rawBlockMaterial(BuiltInRegistries.ITEM.getKey(item).getPath()) == null)
                        .toList())
                .orElse(List.of());
    }

    private static Item blockIn(String namespace, String path) {
        final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        final Item item = BuiltInRegistries.ITEM.get(id);
        // DefaultedRegistry 查不到会返回 air，用 getKey 反查确认它真的注册过。
        return item instanceof BlockItem && id.equals(BuiltInRegistries.ITEM.getKey(item)) ? item : null;
    }

    // ---------------------------------------------------------------- 压缩粗矿块（AllTheCompressed）

    /**
     * {@code allthecompressed:raw_<material>_block_<n>x} -> {@code allthecompressed:<material>_block_<n>x}，
     * 目标方块不存在就跳过（例如压缩方块模组版本太老、或该材质没有对应的压缩成品块）。
     */
    private static int addCompressedRecipes(Map<ResourceLocation, RecipeHolder<?>> merged, Set<Item> alreadySmeltable) {
        int added = 0;
        for (final ResourceLocation sourceId : BuiltInRegistries.ITEM.keySet()) {
            if (!COMPRESSED_NS.equals(sourceId.getNamespace())) {
                continue;
            }
            final Matcher compressed = COMPRESSED.matcher(sourceId.getPath());
            if (!compressed.matches()) {
                continue;
            }
            final Matcher raw = RAW_BLOCK.matcher(compressed.group(1));
            if (!raw.matches()) {
                continue;
            }
            final int level = Integer.parseInt(compressed.group(2));
            final Item source = BuiltInRegistries.ITEM.get(sourceId);
            if (!(source instanceof BlockItem) || alreadySmeltable.contains(source)) {
                continue;
            }
            final String material = raw.group(1);
            final Item result = blockIn(COMPRESSED_NS, material + "_block_" + level + "x");
            if (result == null) {
                continue;
            }
            final ResourceLocation recipeId = recipeId("compressed/" + sourceId.getPath());
            if (merged.containsKey(recipeId)) {
                continue;
            }
            merged.put(recipeId, smelting(recipeId, source, result,
                    rawBlockXp(material) * COMPRESSED_XP_FACTOR * level, SMELTING_TIME));
            alreadySmeltable.add(source);
            added++;
        }
        return added;
    }

    // ---------------------------------------------------------------- 工具方法

    private static ResourceLocation recipeId(String path) {
        return ResourceLocation.fromNamespaceAndPath(RawBlockFurnace.MODID, "auto/" + path);
    }

    private static RecipeHolder<SmeltingRecipe> smelting(ResourceLocation id, Item input, Item output,
                                                         float experience, int cookingTime) {
        return new RecipeHolder<>(id, new SmeltingRecipe(
                "",
                CookingBookCategory.BLOCKS,
                Ingredient.of(input),
                new ItemStack(output),
                experience,
                cookingTime));
    }

    /** 收集"已经被某个熔炉配方处理过"的输入物品，避免与原版/其它模组/本模组的静态 JSON 打架。 */
    private static Set<Item> collectSmeltingInputs(Iterable<RecipeHolder<?>> recipes) {
        final Set<Item> inputs = new LinkedHashSet<>();
        for (final RecipeHolder<?> holder : recipes) {
            if (!(holder.value() instanceof SmeltingRecipe smelting)) {
                continue;
            }
            final List<Ingredient> ingredients = new ArrayList<>(smelting.getIngredients());
            if (ingredients.isEmpty()) {
                continue;
            }
            for (final ItemStack stack : ingredients.get(0).getItems()) {
                inputs.add(stack.getItem());
            }
        }
        return inputs;
    }
}
