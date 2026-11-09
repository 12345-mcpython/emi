package dev.emi.emi.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;

/**
 * Server to client recipe transfer for servers that run EMI.
 * <p>
 * 1.21.2+ clients never receive the vanilla recipe table, so a server with EMI sends its whole recipe map
 * through this channel. It is chunked because a full table easily exceeds the payload size limit, and the
 * receiver only applies the map once the last chunk arrived.
 * <p>
 * The class is loaded on a dedicated server, so it must never reference client only classes.
 */
public class EmiRecipeSyncS2CPacket implements EmiPacket {
	public static final CustomPacketPayload.Type<EmiRecipeSyncS2CPacket> TYPE =
		new CustomPacketPayload.Type<>(ResourceLocation.parse("emi:recipe_sync"));

	/** Set by the client entrypoint; null on a dedicated server. */
	public static volatile Consumer<EmiRecipeSyncS2CPacket> clientHandler;

	private final boolean last;
	private final Map<ResourceLocation, Recipe<?>> recipes;

	public EmiRecipeSyncS2CPacket(boolean last, Map<ResourceLocation, Recipe<?>> recipes) {
		this.last = last;
		this.recipes = recipes;
	}

	public EmiRecipeSyncS2CPacket(RegistryFriendlyByteBuf buf) {
		this.last = buf.readBoolean();
		int count = buf.readVarInt();
		Map<ResourceLocation, Recipe<?>> recipes = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			ResourceLocation serializerId = buf.readResourceLocation();
			ResourceLocation id = buf.readResourceLocation();
			RecipeSerializer<?> serializer = BuiltInRegistries.RECIPE_SERIALIZER.get(serializerId)
				.map(net.minecraft.core.Holder.Reference::value).orElse(null);
			if (serializer == null) {
				throw new IllegalStateException("Unknown recipe serializer " + serializerId);
			}
			recipes.put(id, serializer.streamCodec().decode(buf));
		}
		this.recipes = recipes;
	}

	@Override
	public void write(RegistryFriendlyByteBuf buf) {
		buf.writeBoolean(last);
		buf.writeVarInt(recipes.size());
		for (Map.Entry<ResourceLocation, Recipe<?>> entry : recipes.entrySet()) {
			buf.writeResourceLocation(BuiltInRegistries.RECIPE_SERIALIZER.getKey(entry.getValue().getSerializer()));
			buf.writeResourceLocation(entry.getKey());
			writeRecipe(buf, entry.getValue());
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void writeRecipe(RegistryFriendlyByteBuf buf, Recipe<?> recipe) {
		((RecipeSerializer) recipe.getSerializer()).streamCodec().encode(buf, recipe);
	}

	@Override
	public void apply(Player player) {
		Consumer<EmiRecipeSyncS2CPacket> handler = clientHandler;
		if (handler != null) {
			handler.accept(this);
		}
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public boolean last() {
		return last;
	}

	public Map<ResourceLocation, Recipe<?>> recipes() {
		return recipes;
	}
}
