package com.coreboost;

import com.coreboost.client.ClientRuntime;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

@Mod(modid = "coreboost", name = "CoreBoost", version = "1.0.0", clientSideOnly = true)
public final class CoreBoostMod {
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ClientRuntime.start(event.getSuggestedConfigurationFile());
    }
}

