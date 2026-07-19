package org.Ender_Bryce.create_factory_gauge_enhance;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Create_factory_gauge_enhance.MODID)
public class Create_factory_gauge_enhance {
    public static final String MODID = "create_factory_gauge_enhance";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Create_factory_gauge_enhance() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Create Factory Gauge Enhance loaded!");
    }
}