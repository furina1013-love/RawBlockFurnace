package com.rawblockfurnace;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Raw Block Furnace（Forge 1.20.1）：把粗矿块熔炼成对应金属块。
 *
 * <p>配方分两部分：
 * <ul>
 *   <li>静态 JSON（{@code data/raw_block_furnace/recipes/}）：原版三种粗矿块 + AllTheCompressed 压缩粗矿块，
 *       带 {@code conditions}，所有模组都能用；</li>
 *   <li>运行时注入（{@link RawBlockRecipeInjector}）：按注册名规则通配任意模组的粗矿块。</li>
 * </ul>
 */
@Mod(RawBlockFurnace.MODID)
public class RawBlockFurnace {
    public static final String MODID = "raw_block_furnace";
    private static final Logger LOGGER = LogUtils.getLogger();

    public RawBlockFurnace() {
        // LOWEST = 最后执行：能合并到前面所有模组在同一个事件里做的改动。
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, RawBlockRecipeInjector::onDatapackSync);
        MinecraftForge.EVENT_BUS.addListener(RawBlockRecipeInjector::onServerStarted);
        LOGGER.info("{} loaded", MODID);
    }
}
