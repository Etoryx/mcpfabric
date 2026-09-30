package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Client-side perception for the agent runtime. Everything reads the client's own copy of the
 * world, so it works on any server (vanilla multiplayer included), unlike the server-only
 * {@code world.*} handlers:
 * <ul>
 *   <li>{@code session.info} — a stable id of the world / server being played, to key memory;</li>
 *   <li>{@code perception.scan} — per-chunk terrain and notable block counts, points of interest
 *       (containers, workstations, beds, portals, spawners) and positions of requested blocks;</li>
 *   <li>{@code perception.blocks} — block ids at given positions, optionally with the best hotbar
 *       tool to mine each one;</li>
 *   <li>{@code perception.entities} — nearby dropped items, hostile and passive mobs, players.</li>
 * </ul>
 */
public final class PerceptionHandlers {
	private static final int MAX_RADIUS = 12;
	private static final int MAX_POIS = 400;

	/** Blocks without block entities that are still worth remembering. */
	private static final Set<String> POI_BLOCKS = Set.of(
			"minecraft:crafting_table", "minecraft:smithing_table", "minecraft:stonecutter",
			"minecraft:grindstone", "minecraft:loom", "minecraft:cartography_table",
			"minecraft:fletching_table", "minecraft:anvil", "minecraft:chipped_anvil",
			"minecraft:damaged_anvil", "minecraft:nether_portal", "minecraft:end_portal_frame",
			"minecraft:composter");

	/** Block entities worth remembering even though they hold no items. */
	private static final Set<String> POI_BLOCK_ENTITIES = Set.of(
			"minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault", "minecraft:enchanting_table",
			"minecraft:beacon", "minecraft:lectern", "minecraft:ender_chest", "minecraft:end_gateway");

	private PerceptionHandlers() {}

	public static void register(RpcRouter router) {
		router.register("session.info", ctx -> ClientMc.call(PerceptionHandlers::sessionInfo));

		router.register("perception.scan", ctx -> ClientMc.call(() -> {
			int radius = Math.max(0, Math.min(MAX_RADIUS, ctx.optInt("radius", 6)));
			Set<String> find = new HashSet<>(ctx.getStringList("find"));
			int findLimit = Math.max(1, Math.min(500, ctx.optInt("findLimit", 64)));
			return scan(ClientMc.level(), ClientMc.player(), radius, find, findLimit);
		}));

		router.register("perception.entities", ctx -> ClientMc.call(() -> {
			double radius = Math.max(1, Math.min(64, ctx.optDouble("radius", 16)));
			return entities(ClientMc.level(), ClientMc.player(), radius, new HashSet<>(ctx.getStringList("kinds")));
		}));

		router.register("perception.blocks", ctx -> ClientMc.call(() -> {
			ClientLevel level = ClientMc.level();
			LocalPlayer player = ClientMc.player();
			boolean tool = ctx.optBool("tool", false);
			JsonArray out = new JsonArray();
			if (!ctx.params().has("positions") || !ctx.params().get("positions").isJsonArray()) {
				throw RpcException.badRequest("Missing required param 'positions' (array of {x,y,z}).");
			}
			JsonArray positions = ctx.params().getAsJsonArray("positions");
			if (positions.size() > 512) throw RpcException.badRequest("At most 512 positions per call.");
			for (JsonElement e : positions) {
				JsonObject p = e.getAsJsonObject();
				BlockPos pos = BlockPos.containing(p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble());
				out.add(probe(level, player, pos, tool));
			}
			JsonObject o = new JsonObject();
			o.add("blocks", out);
			return o;
		}));
	}

	// --- session ------------------------------------------------------------------------------

	private static JsonObject sessionInfo() throws RpcException {
		Minecraft mc = ClientMc.mc();
		LocalPlayer player = ClientMc.player();
		JsonObject o = new JsonObject();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			String folder = root.getFileName() == null ? "world" : root.getFileName().toString();
			o.addProperty("worldId", "sp:" + folder);
			o.addProperty("kind", "singleplayer");
			o.addProperty("name", server.getWorldData().getLevelName());
		} else {
			ServerData data = mc.getCurrentServer();
			String address = data != null && data.ip != null ? data.ip.trim().toLowerCase(Locale.ROOT) : "unknown";
			o.addProperty("worldId", "mp:" + address);
			o.addProperty("kind", "multiplayer");
			o.addProperty("name", data != null && data.name != null ? data.name : address);
		}
		o.addProperty("player", player.getName().getString());
		o.addProperty("dimension", Levels.dimensionId(player.level()));
		return o;
	}

	// --- scan ---------------------------------------------------------------------------------

	/** Per-block-type answers (render thread only), so id strings are built once per block type. */
	private static final Map<Block, Boolean> ORE = new IdentityHashMap<>();
	private static final Map<Block, Boolean> POI = new IdentityHashMap<>();

	private static boolean countable(BlockState state) {
		if (state.is(BlockTags.LOGS)) return true;
		return ORE.computeIfAbsent(state.getBlock(), b -> {
			String id = blockId(b);
			return id.endsWith("_ore") || id.equals("minecraft:ancient_debris");
		});
	}

	private static boolean poiBlock(BlockState state) {
		return POI.computeIfAbsent(state.getBlock(), b -> POI_BLOCKS.contains(blockId(b)));
	}

	private static JsonObject scan(ClientLevel level, LocalPlayer player, int radius, Set<String> find, int findLimit) {
		double px = player.getX(), py = player.getY(), pz = player.getZ();
		// ChunkPos became a record (x()/z()) in 26.1; the block position works everywhere.
		int pcx = player.blockPosition().getX() >> 4;
		int pcz = player.blockPosition().getZ() >> 4;
		JsonArray chunks = new JsonArray();
		List<JsonObject> pois = new ArrayList<>();
		Map<Block, Boolean> wantedMemo = new IdentityHashMap<>();
		Predicate<BlockState> wanted = s -> wantedMemo.computeIfAbsent(s.getBlock(), b -> find.contains(blockId(b)));
		// The nearest `findLimit` matches: a max-heap on distance, so the farthest one is evicted.
		// Entries are {x, y, z, exposed, id index, squared distance ×64}.
		PriorityQueue<long[]> found = new PriorityQueue<>((a, b) -> Long.compare(b[5], a[5]));
		List<String> foundIds = new ArrayList<>();

		// Nearest chunks first, so the match search can skip chunks that cannot hold anything closer.
		List<int[]> order = new ArrayList<>();
		for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
			for (int cz = pcz - radius; cz <= pcz + radius; cz++) order.add(new int[]{cx, cz});
		}
		order.sort(Comparator.comparingInt(c -> (c[0] - pcx) * (c[0] - pcx) + (c[1] - pcz) * (c[1] - pcz)));

		for (int[] cc : order) {
			int cx = cc[0], cz = cc[1];
			LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
			if (chunk == null || chunk.isEmpty()) continue;

			JsonObject c = new JsonObject();
			c.addProperty("cx", cx);
			c.addProperty("cz", cz);
			surface(level, chunk, cx * 16 + 8, cz * 16 + 8, c);

			double gx = Math.max(0, Math.max(cx * 16 - px, px - (cx * 16 + 16)));
			double gz = Math.max(0, Math.max(cz * 16 - pz, pz - (cz * 16 + 16)));
			boolean searchChunk = !find.isEmpty()
					&& (found.size() < findLimit || (long) ((gx * gx + gz * gz) * 64) < found.peek()[5]);

			Map<String, Integer> counts = new HashMap<>();
			LevelChunkSection[] sections = chunk.getSections();
			for (int i = 0; i < sections.length; i++) {
				LevelChunkSection section = sections[i];
				if (section.hasOnlyAir()) continue;
				section.getStates().count((state, n) -> {
					if (countable(state)) counts.merge(blockId(state.getBlock()), n, Integer::sum);
				});
				boolean hasPoi = pois.size() < MAX_POIS && section.maybeHas(PerceptionHandlers::poiBlock);
				boolean hasWanted = searchChunk && section.maybeHas(wanted);
				if (!hasPoi && !hasWanted) continue;
				int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
				BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							BlockState state = section.getBlockState(x, y, z);
							if (state.isAir()) continue;
							m.set(cx * 16 + x, baseY + y, cz * 16 + z);
							if (hasPoi && pois.size() < MAX_POIS && poiBlock(state)) {
								pois.add(poi(blockId(state), m, false));
							}
							if (hasWanted && wanted.test(state)) {
								long d = (long) (dist2(m.getX() + 0.5, m.getY() + 0.5, m.getZ() + 0.5, px, py, pz) * 64);
								if (found.size() >= findLimit && d >= found.peek()[5]) continue;
								found.add(new long[]{m.getX(), m.getY(), m.getZ(), exposed(level, m) ? 1 : 0, foundIds.size(), d});
								foundIds.add(blockId(state));
								if (found.size() > findLimit) found.poll();
							}
						}
					}
				}
			}
			JsonObject countJson = new JsonObject();
			counts.forEach(countJson::addProperty);
			c.add("counts", countJson);
			chunks.add(c);

			for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
				if (pois.size() >= MAX_POIS) break;
				BlockEntity be = e.getValue();
				String id = blockId(be.getBlockState());
				boolean container = be instanceof Container;
				if (container || POI_BLOCK_ENTITIES.contains(id) || id.endsWith("_bed")) {
					pois.add(poi(id, e.getKey(), container));
				}
			}
		}

		pois.sort(Comparator.comparingDouble(o -> dist2(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(), px, py, pz)));
		JsonArray poiJson = new JsonArray();
		pois.forEach(poiJson::add);

		List<long[]> nearest = new ArrayList<>(found);
		nearest.sort(Comparator.comparingLong(f -> f[5]));
		JsonArray foundJson = new JsonArray();
		for (long[] f : nearest) {
			JsonObject o = new JsonObject();
			o.addProperty("id", foundIds.get((int) f[4]));
			o.addProperty("x", f[0]);
			o.addProperty("y", f[1]);
			o.addProperty("z", f[2]);
			o.addProperty("exposed", f[3] == 1);
			foundJson.add(o);
		}

		JsonObject o = new JsonObject();
		o.addProperty("dimension", Levels.dimensionId(level));
		JsonObject pos = new JsonObject();
		pos.addProperty("x", px);
		pos.addProperty("y", py);
		pos.addProperty("z", pz);
		o.add("player", pos);
		o.addProperty("radius", radius);
		o.add("chunks", chunks);
		o.add("pois", poiJson);
		if (!find.isEmpty()) o.add("found", foundJson);
		o.addProperty("truncated", pois.size() >= MAX_POIS);
		return o;
	}

	/**
	 * Standable surface at a column: feet height above the topmost motion-blocking block that is
	 * not leaves or a log (tree canopies are not ground). Liquid surfaces and dimensions with a
	 * ceiling (the Nether roof) get no {@code y}, so the runtime never plans to walk there.
	 */
	private static void surface(ClientLevel level, LevelChunk chunk, int x, int z, JsonObject out) {
		int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
		int minY = minY(level);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, top, z);
		BlockState state = chunk.getBlockState(m);
		while (m.getY() > minY && (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS))) {
			m.move(Direction.DOWN);
			state = chunk.getBlockState(m);
		}
		if (state.getFluidState().isEmpty() && !level.dimensionType().hasCeiling()) out.addProperty("y", m.getY() + 1);
		level.getBiome(m).unwrapKey().ifPresent(key -> out.addProperty("biome", resourceKeyId(key)));
	}

	// --- entities -----------------------------------------------------------------------------

	private static String entityKind(Entity e) {
		if (e instanceof ItemEntity) return "item";
		if (e instanceof Player) return "player";
		if (e instanceof Enemy) return "hostile";
		if (e instanceof LivingEntity) return "passive";
		return "other";
	}

	private static JsonObject entities(ClientLevel level, LocalPlayer player, double radius, Set<String> kinds) {
		List<Entity> list = level.getEntities(player, player.getBoundingBox().inflate(radius), e -> e.isAlive()
				&& (kinds.isEmpty() ? !entityKind(e).equals("other") : kinds.contains(entityKind(e))));
		list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
		JsonArray out = new JsonArray();
		for (Entity e : list) {
			if (out.size() >= 64) break;
			JsonObject o = new JsonObject();
			o.addProperty("uuid", e.getUUID().toString());
			o.addProperty("type", String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())));
			o.addProperty("kind", entityKind(e));
			o.addProperty("x", e.getX());
			o.addProperty("y", e.getY());
			o.addProperty("z", e.getZ());
			o.addProperty("distance", Math.sqrt(e.distanceToSqr(player)));
			if (e instanceof LivingEntity living) o.addProperty("health", living.getHealth());
			if (e instanceof ItemEntity item) {
				JsonObject stack = new JsonObject();
				stack.addProperty("id", String.valueOf(BuiltInRegistries.ITEM.getKey(item.getItem().getItem())));
				stack.addProperty("count", item.getItem().getCount());
				o.add("item", stack);
			}
			out.add(o);
		}
		JsonObject o = new JsonObject();
		o.add("entities", out);
		return o;
	}

	private static boolean exposed(ClientLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
		}
		return false;
	}

	private static JsonObject poi(String id, BlockPos pos, boolean container) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("x", pos.getX());
		o.addProperty("y", pos.getY());
		o.addProperty("z", pos.getZ());
		if (container) o.addProperty("container", true);
		return o;
	}

	// --- blocks -------------------------------------------------------------------------------

	private static JsonObject probe(ClientLevel level, LocalPlayer player, BlockPos pos, boolean tool) {
		JsonObject o = new JsonObject();
		o.addProperty("x", pos.getX());
		o.addProperty("y", pos.getY());
		o.addProperty("z", pos.getZ());
		boolean loaded = level.isLoaded(pos) && level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) != null;
		o.addProperty("loaded", loaded);
		if (!loaded) return o;
		BlockState state = level.getBlockState(pos);
		o.addProperty("id", blockId(state));
		o.addProperty("air", state.isAir());
		if (state.isAir()) return o;
		boolean requiresTool = state.requiresCorrectToolForDrops();
		o.addProperty("requiresTool", requiresTool);
		o.addProperty("hardness", state.getDestroySpeed(level, pos));
		if (tool) {
			Inventory inv = player.getInventory();
			int selected = selectedSlot(inv);
			int best = selected;
			boolean bestHarvests = !requiresTool || inv.getItem(selected).isCorrectToolForDrops(state);
			float bestSpeed = inv.getItem(selected).getDestroySpeed(state);
			for (int slot = 0; slot < 9; slot++) {
				ItemStack stack = inv.getItem(slot);
				boolean harvests = !requiresTool || stack.isCorrectToolForDrops(state);
				float speed = stack.getDestroySpeed(state);
				if ((harvests && !bestHarvests) || (harvests == bestHarvests && speed > bestSpeed)) {
					best = slot;
					bestHarvests = harvests;
					bestSpeed = speed;
				}
			}
			o.addProperty("bestSlot", best);
			o.addProperty("canHarvest", bestHarvests);
		}
		return o;
	}

	// --- version shims ------------------------------------------------------------------------

	private static int selectedSlot(Inventory inv) {
		//? if >=1.21.5 {
		return inv.getSelectedSlot();
		//?} else
		/*return inv.selected;*/
	}

	private static int minY(ClientLevel level) {
		//? if >=1.21.2 {
		return level.getMinY();
		//?} else
		/*return level.getMinBuildHeight();*/
	}

	private static String resourceKeyId(net.minecraft.resources.ResourceKey<?> key) {
		//? if <1.21.11 {
		return key.location().toString();
		//?} else
		/*return key.identifier().toString();*/
	}

	static String blockId(BlockState state) {
		return blockId(state.getBlock());
	}

	static String blockId(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	private static double dist2(double x, double y, double z, double px, double py, double pz) {
		double dx = x - px, dy = y - py, dz = z - pz;
		return dx * dx + dy * dy + dz * dz;
	}
}
