package com.rawblockfurnace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * 运行时"通配"注入（Forge 1.20.1 版）：让<b>任何模组</b>的粗矿块都能在熔炉里熔成对应的金属块，
 * 压缩方块模组（AllTheCompressed）的压缩粗矿块也能熔成对应的压缩成品块。
 *
 * <p>为什么不用 JSON：粗矿块与其成品块的注册名由各模组自行决定（{@code mekanism:block_raw_osmium}
 * 对应 {@code mekanism:block_osmium}、{@code alltheores:raw_tin_block} 对应
 * {@code alltheores:tin_block}…），datapack 无法表达"按名字推导"，result 也不支持 tag。
 * 因此这里在服务端运行时扫注册表，把原版 {@link SmeltingRecipe} 用
 * {@link RecipeManager#replaceRecipes(Iterable)} 塞进配方表。
 *
 * <p>时机：Forge 的 {@link OnDatapackSyncEvent} 在登录与 {@code /reload} 两条路径上都
 * <b>早于</b>向客户端广播 {@code ClientboundUpdateRecipesPacket}，所以改完的配方会随包同步，
 * JEI 也能读到；{@code /reload} 会重建 RecipeManager，所以每次事件都要（幂等地）重新注入。
 */
public final class RawBlockRecipeInjector {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 原版 / AllTheOres / Thermal 风格：{@code raw_<material>_block}。 */
    private static final Pattern RAW_BLOCK = Pattern.compile("raw_([a-z0-9_]+)_block");
    /** Immersive Engineering 风格：{@code raw_block_<material>}。 */
    private static final Pattern RAW_BLOCK_PREFIXED = Pattern.compile("raw_block_([a-z0-9_]+)");
    /** Mekanism 风格：{@code block_raw_<material>}。 */
    private static final Pattern RAW_BLOCK_MEKANISM = Pattern.compile("block_raw_([a-z0-9_]+)");
    /** 压缩方块模组命名：{@code <原方块 id>_<n>x}，n = 1..9。 */
    private static final Pattern COMPRESSED = Pattern.compile("([a-z0-9_]+)_([1-9])x");
    private static final String COMPRESSED_NS = "allthecompressed";

    private static final int SMELTING_TIME = 200;
    private static final float RAW_BLOCK_XP = 6.3F;
    /** 压缩级经验 = 单块经验 x 9 x n（1x = 相当于熔炼 9 个粗矿块）。 */
    private static final float COMPRESSED_XP_FACTOR = 9F;

    private RawBlockRecipeInjector() {}

    /**
     * 单块粗矿块的熔炼经验：默认按"9 个粗矿物品"折算（铁/铜 0.7 x 9 = 6.3）；
     * 金锭是 1.0，所以金用 9.0（与静态 JSON 一致）。其它材质无法判断，统一 6.3。
     */
    private static float rawBlockXp(String material) {
        return "gold".equals(material) ? 9.0F : RAW_BLOCK_XP;
    }

    public static void onDatapackSync(OnDatapackSyncEvent event) {
        inject(event.getPlayerList().getServer().getRecipeManager());
    }

    public static void onServerStarted(ServerStartedEvent event) {
        inject(event.getServer().getRecipeManager());
    }

    private static void inject(RecipeManager recipeManager) {
        final List<Recipe<?>> all = new ArrayList<>(recipeManager.getRecipes());
        final Set<ResourceLocation> existingIds = new LinkedHashSet<>();
        for (final Recipe<?> recipe : all) {
            existingIds.add(recipe.getId());
        }
        final Set<Item> alreadySmeltable = collectSmeltingInputs(all);

        // 压缩级优先：万一整合包把压缩粗矿块也打进了 c:storage_blocks/raw_<material> 标签，
        // 也要先按压缩规则生成，别让标签兜底先占位成普通 200 tick 的配方。
        final int compressed = addCompressedRecipes(all, existingIds, alreadySmeltable);
        final int normal = addRawBlockRecipes(all, existingIds, alreadySmeltable);

        if (normal + compressed > 0) {
            recipeManager.replaceRecipes(all);
            LOGGER.info("[{}] injected {} runtime smelting recipes (raw blocks: {}, compressed: {})",
                    RawBlockFurnace.MODID, normal + compressed, normal, compressed);
        } else {
            LOGGER.debug("[{}] no new runtime smelting recipes to inject", RawBlockFurnace.MODID);
        }
    }

    // ---------------------------------------------------------------- 原版风格粗矿块

    private static int addRawBlockRecipes(List<Recipe<?>> all, Set<ResourceLocation> existingIds,
                                          Set<Item> alreadySmeltable) {
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
            if (!existingIds.add(recipeId)) {
                continue;
            }
            all.add(smelting(recipeId, source, result, rawBlockXp(material), SMELTING_TIME));
            alreadySmeltable.add(source);
            added++;
        }
        added += addTagDrivenRecipes(all, existingIds, alreadySmeltable);
        return added;
    }

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

    /** 标签驱动兜底：{@code c:storage_blocks/raw_<material>} ↔ {@code c:storage_blocks/<material>}。 */
    private static int addTagDrivenRecipes(List<Recipe<?>> all, Set<ResourceLocation> existingIds,
                                           Set<Item> alreadySmeltable) {
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
                if (!existingIds.add(recipeId)) {
                    continue;
                }
                all.add(smelting(recipeId, source, result, rawBlockXp(material), SMELTING_TIME));
                alreadySmeltable.add(source);
                added++;
            }
        }
        return added;
    }

    private static TagKey<Item> tagOf(String path) {
        return TagKey.create(Registries.ITEM, new ResourceLocation("c", path));
    }

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
        final ResourceLocation id = new ResourceLocation(namespace, path);
        final Item item = BuiltInRegistries.ITEM.get(id);
        // DefaultedRegistry 查不到会返回 air，用 getKey 反查确认它真的注册过。
        return item instanceof BlockItem && id.equals(BuiltInRegistries.ITEM.getKey(item)) ? item : null;
    }

    // ---------------------------------------------------------------- 压缩粗矿块

    private static int addCompressedRecipes(List<Recipe<?>> all, Set<ResourceLocation> existingIds,
                                            Set<Item> alreadySmeltable) {
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
            if (!existingIds.add(recipeId)) {
                continue;
            }
            all.add(smelting(recipeId, source, result,
                    rawBlockXp(material) * COMPRESSED_XP_FACTOR * level, SMELTING_TIME));
            alreadySmeltable.add(source);
            added++;
        }
        return added;
    }

    // ---------------------------------------------------------------- 工具方法

    private static ResourceLocation recipeId(String path) {
        return new ResourceLocation(RawBlockFurnace.MODID, "auto/" + path);
    }

    private static SmeltingRecipe smelting(ResourceLocation id, Item input, Item output, float experience, int cookingTime) {
        return new SmeltingRecipe(id, "", CookingBookCategory.BLOCKS, Ingredient.of(input),
                new ItemStack(output), experience, cookingTime);
    }

    /** 收集"已经被某个熔炉配方处理过"的输入物品，避免与原版/其它模组/本模组静态 JSON 打架。 */
    private static Set<Item> collectSmeltingInputs(List<Recipe<?>> recipes) {
        final Set<Item> inputs = new LinkedHashSet<>();
        for (final Recipe<?> recipe : recipes) {
            if (!(recipe instanceof SmeltingRecipe smelting)) {
                continue;
            }
            final List<Ingredient> ingredients = smelting.getIngredients();
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
