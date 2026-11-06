package dev.emi.emi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import dev.emi.emi.runtime.EmiLog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

@Mixin(ClientboundUpdateRecipesPacket.class)
public class SynchronizeRecipesS2CPacketMixin {
	
	@Inject(at = @At("RETURN"), method = "fromNetwork", locals = LocalCapture.CAPTURE_FAILHARD)
	private static void readRecipe(FriendlyByteBuf buf, CallbackInfoReturnable<Recipe<?>> info, ResourceLocation serializer, ResourceLocation id) {
		Recipe<?> recipe = info.getReturnValue();
		if (recipe == null) {
			EmiLog.error("Recipe " + id + " was deserialized as null using recipe serializer " + serializer
				+ ", this is breaking vanilla recipe sync, and will prevent EMI from loading recipes");
		}
	}
}
