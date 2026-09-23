package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Vision: framebuffer screenshot (for vision models) and a structured scene description. */
public final class VisionHandlers {
	private VisionHandlers() {}

	public static void register(RpcRouter router) {
		router.register("vision.screenshot", ctx -> {
			if (!McpFabric.config().enableVision) {
				throw RpcException.unavailable("Vision is disabled in mcpfabric.config.json (enableVision=false).");
			}
			Minecraft mc = ClientMc.mc();
			if (mc == null || mc.player == null || mc.level == null) throw RpcException.noClientPlayer();

			// takeScreenshot performs the GPU readback (new render pipeline) and hands us a CPU-side
			// NativeImage via a callback that may fire after this frame, so coordinate via a future.
			CompletableFuture<JsonObject> future = new CompletableFuture<>();
			mc.execute(() -> {
				try {
					//? if >=1.21.5 {
					Screenshot.takeScreenshot(mainRenderTarget(mc), image -> {
						try {
							future.complete(imageToJson(image));
						} catch (Exception e) {
							future.completeExceptionally(e);
						} finally {
							image.close();
						}
					});
					//?} else {
					/*NativeImage image = Screenshot.takeScreenshot(mainRenderTarget(mc));
					try {
						future.complete(imageToJson(image));
					} finally {
						image.close();
					}
					*///?}
				} catch (Throwable t) {
					future.completeExceptionally(t);
				}
			});

			try {
				return future.get(Math.max(5000, McpFabric.config().callTimeoutMs), TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				throw new RpcException("screenshot_failed", "Failed to capture screenshot: " + cause.getMessage());
			}
		});

		router.register("vision.describeScene", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			ClientLevel level = ClientMc.level();
			Minecraft mc = ClientMc.mc();
			double maxDistance = ctx.optDouble("maxDistance", 48.0);
			int cols = ctx.optInt("rayColumns", 9);
			int rows = ctx.optInt("rayRows", 5);

			JsonObject o = new JsonObject();
			Vec3 eye = p.getEyePosition();
			o.add("eye", vec(eye));
			o.addProperty("yaw", p.getYRot());
			o.addProperty("pitch", p.getXRot());

			// What the crosshair is on.
			HitResult hit = mc.hitResult;
			if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
				BlockPos bp = bhr.getBlockPos();
				JsonObject la = new JsonObject();
				la.addProperty("type", "block");
				la.addProperty("id", BuiltInRegistries.BLOCK.getKey(level.getBlockState(bp).getBlock()).toString());
				la.add("pos", blockPos(bp));
				o.add("lookingAt", la);
			} else if (hit instanceof EntityHitResult ehr) {
				JsonObject la = new JsonObject();
				la.addProperty("type", "entity");
				la.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(ehr.getEntity().getType()).toString());
				la.addProperty("name", ehr.getEntity().getName().getString());
				o.add("lookingAt", la);
			} else {
				JsonObject la = new JsonObject();
				la.addProperty("type", "none");
				o.add("lookingAt", la);
			}

			// Ray grid across the field of view.
			JsonArray grid = new JsonArray();
			float baseYaw = p.getYRot();
			float basePitch = p.getXRot();
			for (int r = 0; r < rows; r++) {
				float pitchOff = rows == 1 ? 0 : -30f + 60f * r / (rows - 1);
				for (int c = 0; c < cols; c++) {
					float yawOff = cols == 1 ? 0 : -45f + 90f * c / (cols - 1);
					Vec3 dir = dirFromAngles(baseYaw + yawOff, basePitch + pitchOff);
					JsonObject ray = stepRay(level, eye, dir, maxDistance);
					ray.addProperty("col", c);
					ray.addProperty("row", r);
					grid.add(ray);
				}
			}
			o.add("rays", grid);
			return o;
		}));
	}

	/** The main framebuffer. {@code Minecraft.getMainRenderTarget()} moved to {@code gameRenderer.mainRenderTarget()} in 26.2. */
	private static RenderTarget mainRenderTarget(Minecraft mc) {
		//? if <26.2 {
		return mc.getMainRenderTarget();
		//?} else
		/*return mc.gameRenderer.mainRenderTarget();*/
	}

	/** Encode a captured frame as a PNG base64 payload. */
	private static JsonObject imageToJson(NativeImage image) throws IOException {
		Path tmp = Files.createTempFile("mcpfabric_shot", ".png");
		image.writeToFile(tmp);
		byte[] bytes = Files.readAllBytes(tmp);
		Files.deleteIfExists(tmp);
		JsonObject o = new JsonObject();
		o.addProperty("format", "png");
		o.addProperty("width", image.getWidth());
		o.addProperty("height", image.getHeight());
		o.addProperty("bytes", bytes.length);
		o.addProperty("base64", Base64.getEncoder().encodeToString(bytes));
		return o;
	}

	private static JsonObject stepRay(ClientLevel level, Vec3 start, Vec3 dir, double maxDistance) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (double t = 0.2; t <= maxDistance; t += 0.25) {
			Vec3 pp = start.add(dir.scale(t));
			m.set((int) Math.floor(pp.x), (int) Math.floor(pp.y), (int) Math.floor(pp.z));
			if (!level.hasChunkAt(m)) break;
			BlockState st = level.getBlockState(m);
			if (!st.isAir()) {
				JsonObject o = new JsonObject();
				o.addProperty("hit", true);
				o.addProperty("id", BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
				o.addProperty("distance", t);
				o.add("pos", blockPos(m));
				return o;
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("hit", false);
		return o;
	}

	private static Vec3 dirFromAngles(float yawDeg, float pitchDeg) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		return new Vec3(-Math.cos(pitch) * Math.sin(yaw), -Math.sin(pitch), Math.cos(pitch) * Math.cos(yaw));
	}

	private static JsonObject vec(Vec3 v) {
		JsonObject o = new JsonObject();
		o.addProperty("x", v.x);
		o.addProperty("y", v.y);
		o.addProperty("z", v.z);
		return o;
	}

	private static JsonObject blockPos(BlockPos p) {
		JsonObject o = new JsonObject();
		o.addProperty("x", p.getX());
		o.addProperty("y", p.getY());
		o.addProperty("z", p.getZ());
		return o;
	}
}
