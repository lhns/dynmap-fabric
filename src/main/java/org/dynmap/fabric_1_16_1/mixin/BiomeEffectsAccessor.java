package org.dynmap.fabric_1_16_1.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.world.biome.BiomeEffects.class)
public interface BiomeEffectsAccessor {
    @Accessor
    int getWaterColor();
}
