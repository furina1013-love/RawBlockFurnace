package com.rawblockfurnace;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

@Mod(RawBlockFurnace.MODID)
public class RawBlockFurnace {
    // [mc:fields]
    public static final String MODID = "raw_block_furnace";
    private static final Logger LOGGER = LogUtils.getLogger();

    public RawBlockFurnace(IEventBus modEventBus) {
        // mod bus（IEventBus）：模组生命周期事件与 DeferredRegister 的注册挂载点。
        // game bus（NeoForge.EVENT_BUS）：游戏运行期事件；@SubscribeEvent 方法所在对象需先注册。
        NeoForge.EVENT_BUS.register(this);
        // [mc:init]
        // 运行时注入"粗矿块 -> 金属块"熔炼配方：覆盖所有模组的粗矿块与压缩方块模组的压缩粗矿块。
        // 时机见 AutoRecipeInjector 注释（OnDatapackSyncEvent，先于配方包广播）。
        NeoForge.EVENT_BUS.addListener(AutoRecipeInjector::onServerStarted);
        // LOWEST = 最后执行：能合并到前面所有模组（KubeJS / ATM 等）在同一个事件里做的改动。
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, AutoRecipeInjector::onDatapackSync);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("{} server starting", MODID);
    }
}
