package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
//? if >=1.21.2 {
import dev.mcpfabric.bridge.MainThread;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

import java.util.List;
import java.util.UUID;
//?}

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Recipes for the agent runtime's crafting planner, and recipe-book placement to execute them.
 *
 * <p>Sources differ by version. Up to 1.21.1 the client receives every recipe. From 1.21.2 the
 * server only sends the recipe-book entries the player has unlocked; in singleplayer the integrated
 * server's full recipe list is added (marked {@code known: false}, since the recipe book can only
 * place unlocked recipes). Every recipe is reported as its result, its type and one list of
 * accepted item ids per filled slot, which works for modded recipe types that provide displays.
 */
public final class RecipeHandlers {
	private static final int MAX_ALTERNATIVES = 16;

	private RecipeHandlers() {}

	public static void register(RpcRouter router) {
		router.register("recipes.query", ctx -> {
			Set<String> wanted = new HashSet<>(ctx.getStringList("items"));
			if (wanted.isEmpty()) throw RpcException.badRequest("'items' must list at least one item id.");
			boolean includeUnknown = ctx.optBool("includeUnknown", true);
			JsonArray recipes = ClientMc.call(() -> clientRecipes(wanted, includeUnknown));
			String source;
			//? if >=1.21.2 {
			source = "recipe_book";
			MinecraftServer server = ClientMc.mc().getSingleplayerServer();
			if (includeUnknown && server != null) {
				UUID player = ClientMc.call(() -> ClientMc.player().getUUID());
				JsonArray locked = MainThread.call(server, McpFabric.config().callTimeoutMs, () -> serverRecipes(server, player, wanted));
				locked.forEach(recipes::add);
				source = "recipe_book+integrated_server";
			}
			//?} else
			/*source = "recipe_manager";*/
			JsonObject o = new JsonObject();
			o.addProperty("source", source);
			o.add("recipes", recipes);
			return o;
		});

		router.register("craft.place", ctx -> ClientMc.call(() -> {
			if (!McpFabric.config().enablePlayerControl) {
				throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
			}
			LocalPlayer p = ClientMc.player();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			String ref = ctx.getString("ref");
			boolean all = ctx.optBool("all", false);
			int containerId = p.containerMenu.containerId;
			//? if >=1.21.2 {
			if (!ref.startsWith("display:")) {
				throw RpcException.badRequest("Recipe " + ref + " is not unlocked yet, so the recipe book cannot place it.");
			}
			int index;
			try {
				index = Integer.parseInt(ref.substring("display:".length()));
			} catch (NumberFormatException e) {
				throw RpcException.badRequest("Bad recipe ref: " + ref);
			}
			gm.handlePlaceRecipe(containerId, new RecipeDisplayId(index), all);
			//?} else {
			/*if (!ref.startsWith("recipe:")) throw RpcException.badRequest("Bad recipe ref: " + ref);
			var holder = p.connection.getRecipeManager()
					.byKey(net.minecraft.resources.ResourceLocation.parse(ref.substring("recipe:".length())))
					.orElseThrow(() -> RpcException.notFound("Unknown recipe " + ref));
			gm.handlePlaceRecipe(containerId, holder, all);
			*///?}
			JsonObject o = new JsonObject();
			o.addProperty("containerId", containerId);
			o.addProperty("note", "The server fills the grid; poll container.state until slot 0 holds the result.");
			return o;
		}));
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static JsonObject recipe(String type, ItemStack result, JsonArray ingredients) {
		JsonObject r = new JsonObject();
		r.addProperty("type", type);
		JsonObject res = new JsonObject();
		res.addProperty("id", itemId(result));
		res.addProperty("count", result.getCount());
		r.add("result", res);
		r.add("ingredients", ingredients);
		return r;
	}

	//? if >=1.21.2 {
	/** Unlocked recipes from the client's recipe book. */
	private static JsonArray clientRecipes(Set<String> wanted, boolean includeUnknown) throws RpcException {
		LocalPlayer p = ClientMc.player();
		ContextMap context = SlotDisplayContext.fromLevel(ClientMc.level());
		JsonArray out = new JsonArray();
		for (RecipeCollection collection : p.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				JsonObject r = fromDisplay(entry.display(), context, wanted);
				if (r == null) continue;
				r.addProperty("ref", "display:" + entry.id().index());
				r.addProperty("known", true);
				out.add(r);
			}
		}
		return out;
	}

	/** Recipes the player has not unlocked yet, from the integrated server (runs on its thread). */
	private static JsonArray serverRecipes(MinecraftServer server, UUID playerId, Set<String> wanted) {
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		ContextMap context = SlotDisplayContext.fromLevel(server.overworld());
		JsonArray out = new JsonArray();
		for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
			if (player != null && player.getRecipeBook().contains(holder.id())) continue; // already in the book
			for (RecipeDisplay display : holder.value().display()) {
				JsonObject r = fromDisplay(display, context, wanted);
				if (r == null) continue;
				r.addProperty("ref", "recipe:" + keyId(holder.id()));
				r.addProperty("known", false);
				out.add(r);
			}
		}
		return out;
	}

	private static String keyId(net.minecraft.resources.ResourceKey<?> key) {
		//? if <1.21.11 {
		return key.location().toString();
		//?} else
		/*return key.identifier().toString();*/
	}

	/** A recipe display as JSON, or null when its result is not one of {@code wanted}. */
	private static JsonObject fromDisplay(RecipeDisplay display, ContextMap context, Set<String> wanted) {
		ItemStack result = display.result().resolveForFirstStack(context);
		if (result.isEmpty() || !wanted.contains(itemId(result))) return null;
		JsonArray ingredients = new JsonArray();
		String station = firstId(display.craftingStation(), context);
		JsonObject r;
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			shaped.ingredients().forEach(s -> addSlot(ingredients, s, context));
			r = recipe("crafting", result, ingredients);
			r.addProperty("width", shaped.width());
			r.addProperty("height", shaped.height());
		} else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			shapeless.ingredients().forEach(s -> addSlot(ingredients, s, context));
			r = recipe("crafting", result, ingredients);
			r.addProperty("shapeless", true);
		} else if (display instanceof FurnaceRecipeDisplay furnace) {
			addSlot(ingredients, furnace.ingredient(), context);
			r = recipe(cookingType(station), result, ingredients);
		} else if (display instanceof StonecutterRecipeDisplay cutter) {
			addSlot(ingredients, cutter.input(), context);
			r = recipe("stonecutting", result, ingredients);
		} else if (display instanceof SmithingRecipeDisplay smithing) {
			for (SlotDisplay s : List.of(smithing.template(), smithing.base(), smithing.addition())) addSlot(ingredients, s, context);
			r = recipe("smithing", result, ingredients);
		} else {
			r = recipe("other", result, ingredients);
		}
		if (station != null) r.addProperty("station", station);
		return r;
	}

	private static String cookingType(String station) {
		if (station == null) return "smelting";
		if (station.endsWith("blast_furnace")) return "blasting";
		if (station.endsWith("smoker")) return "smoking";
		if (station.endsWith("campfire")) return "campfire_cooking";
		return "smelting";
	}

	private static String firstId(SlotDisplay display, ContextMap context) {
		ItemStack stack = display.resolveForFirstStack(context);
		return stack.isEmpty() ? null : itemId(stack);
	}

	private static void addSlot(JsonArray ingredients, SlotDisplay display, ContextMap context) {
		Set<String> ids = new LinkedHashSet<>();
		for (ItemStack stack : display.resolveForStacks(context)) {
			if (!stack.isEmpty()) ids.add(itemId(stack));
			if (ids.size() >= MAX_ALTERNATIVES) break;
		}
		if (ids.isEmpty()) return; // empty cell of a shaped recipe
		JsonArray alts = new JsonArray();
		ids.forEach(alts::add);
		ingredients.add(alts);
	}
	//?} else {
	/*// Every recipe the client knows about (1.21.1 sends them all), flagged by recipe-book state.
	private static JsonArray clientRecipes(Set<String> wanted, boolean includeUnknown) throws RpcException {
		LocalPlayer p = ClientMc.player();
		var registries = ClientMc.level().registryAccess();
		var book = p.getRecipeBook();
		JsonArray out = new JsonArray();
		for (net.minecraft.world.item.crafting.RecipeHolder<?> holder : p.connection.getRecipeManager().getRecipes()) {
			var value = holder.value();
			ItemStack result = value.getResultItem(registries);
			if (result.isEmpty() || !wanted.contains(itemId(result))) continue;
			boolean known = book.contains(holder);
			if (!known && !includeUnknown) continue;
			JsonArray ingredients = new JsonArray();
			for (net.minecraft.world.item.crafting.Ingredient ingredient : value.getIngredients()) {
				Set<String> ids = new LinkedHashSet<>();
				for (ItemStack stack : ingredient.getItems()) {
					if (!stack.isEmpty()) ids.add(itemId(stack));
					if (ids.size() >= MAX_ALTERNATIVES) break;
				}
				if (ids.isEmpty()) continue;
				JsonArray alts = new JsonArray();
				ids.forEach(alts::add);
				ingredients.add(alts);
			}
			String typeKey = String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(value.getType()));
			String type = typeKey.startsWith("minecraft:") ? typeKey.substring("minecraft:".length()) : typeKey;
			JsonObject r = recipe(type, result, ingredients);
			if (value instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
				r.addProperty("width", shaped.getWidth());
				r.addProperty("height", shaped.getHeight());
			} else if (value instanceof net.minecraft.world.item.crafting.ShapelessRecipe) {
				r.addProperty("shapeless", true);
			}
			r.addProperty("ref", "recipe:" + holder.id());
			r.addProperty("known", known);
			out.add(r);
		}
		return out;
	}
	*///?}
}
