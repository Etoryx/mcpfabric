package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.handlers.support.Gates;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** Movement and look control for the local player. */
public final class ControlHandlers {
	private ControlHandlers() {}

	public static void register(RpcRouter router) {
		router.register("control.setInput", ctx -> {
			Gates.playerControl();
			BotController.get().setMovement(
					ctx.optBoolean("forward"),
					ctx.optBoolean("back"),
					ctx.optBoolean("left"),
					ctx.optBoolean("right"),
					ctx.optBoolean("jump"),
					ctx.optBoolean("sneak"),
					ctx.optBoolean("sprint"));
			return Json.ok("input updated");
		});

		router.register("control.stop", ctx -> {
			BotController.get().stopAllMovement();
			return Json.ok("stopped");
		});

		router.register("control.jumpOnce", ctx -> {
			Gates.playerControl();
			BotController.get().jumpOnce();
			return Json.ok("jump");
		});

		router.register("control.look", ctx -> ClientMc.call(() -> {
			Gates.playerControl();
			LocalPlayer p = ClientMc.player();
			float yaw = p.getYRot();
			float pitch = p.getXRot();
			if (ctx.has("yaw")) yaw = (float) ctx.getDouble("yaw");
			if (ctx.has("pitch")) pitch = (float) ctx.getDouble("pitch");
			if (ctx.has("deltaYaw")) yaw += (float) ctx.getDouble("deltaYaw");
			if (ctx.has("deltaPitch")) pitch += (float) ctx.getDouble("deltaPitch");
			pitch = Mth.clamp(pitch, -90.0F, 90.0F);
			applyLook(p, yaw, pitch);
			return look(p);
		}));

		router.register("control.lookAt", ctx -> ClientMc.call(() -> {
			Gates.playerControl();
			LocalPlayer p = ClientMc.player();
			double dx = ctx.getDouble("x") - p.getX();
			double dy = ctx.getDouble("y") - p.getEyeY();
			double dz = ctx.getDouble("z") - p.getZ();
			double horiz = Math.sqrt(dx * dx + dz * dz);
			float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
			float pitch = (float) (-(Mth.atan2(dy, horiz) * (180.0 / Math.PI)));
			applyLook(p, yaw, Mth.clamp(pitch, -90.0F, 90.0F));
			return look(p);
		}));

		router.register("control.startUsing", ctx -> ClientMc.call(() -> {
			Gates.playerControl();
			ClientMc.mc().options.keyUse.setDown(true);
			return Json.ok("using");
		}));

		router.register("control.stopUsing", ctx -> ClientMc.call(() -> {
			ClientMc.mc().options.keyUse.setDown(false);
			ClientMc.player().stopUsingItem();
			return Json.ok("stopped using");
		}));
	}

	private static void applyLook(LocalPlayer p, float yaw, float pitch) {
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
	}

	private static JsonObject look(LocalPlayer p) {
		JsonObject o = new JsonObject();
		o.addProperty("yaw", p.getYRot());
		o.addProperty("pitch", p.getXRot());
		return o;
	}
}
