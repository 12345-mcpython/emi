package dev.emi.emi.mixin.accessor;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ModelManager.class)
public interface BakedModelManagerAccessor {

    @Accessor("bakedRegistry")
    Map<ModelResourceLocation, BakedModel> getModels();
}
