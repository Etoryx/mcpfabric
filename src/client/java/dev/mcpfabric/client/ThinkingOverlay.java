package dev.mcpfabric.client;

import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcRouter;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Renders a sleek, hardware-interrupt styled "thinking" status overlay at the top-right of the screen.
 * Features a slide-in spring animation, glowing pulsing text, and static indicator accents.
 */
public final class ThinkingOverlay {
	private static volatile boolean thinking = false;
	private static volatile String label = "AI Thinking";
	private static volatile long stateChangeTime = 0L;
	private static volatile long autoHideTimeoutMs = 60000L;

	private ThinkingOverlay() {}

	public static void register(RpcRouter router) {
		router.register("ui.setThinking", ctx -> {
			boolean isThinking = ctx.optBool("thinking", true);
			String customLabel = ctx.optString("label", "AI Thinking");
			long timeoutMs = ctx.optLong("timeoutMs", 60000L);
			// Clamp to schema range 1000..300000 and keep in sync with tools.ts:629
			timeoutMs = Math.min(Math.max(timeoutMs, 1000L), 300000L);

			setThinking(isThinking, customLabel, timeoutMs);

			JsonObject o = new JsonObject();
			o.addProperty("thinking", thinking);
			o.addProperty("label", label);
			return o;
		});

		// HudRenderCallback is deprecated since Fabric API 0.107 (1.21.6+) in favour of
		// HudLayerRegistrationCallback / Gui.LayeredDraw, but remains functional across
		// 1.21.1–1.21.11. Keep it for multi-version compatibility via Stonecutter.
		// When bumping to 1.21.6+ only, migrate to LayeredDraw.
		//? if <1.21.6 {
		HudRenderCallback.EVENT.register((guiGraphics, tickCounter) -> {
			long now = System.currentTimeMillis();

			// Auto-hide is checked on render thread; fields are volatile for cross-thread visibility
			if (thinking && autoHideTimeoutMs > 0 && now - stateChangeTime > autoHideTimeoutMs) {
				setThinking(false, label, autoHideTimeoutMs);
			}

			render(guiGraphics, now);
		});
		//?} else {
		/*HudRenderCallback.EVENT.register((guiGraphics, tickCounter) -> {
			long now = System.currentTimeMillis();
			if (thinking && autoHideTimeoutMs > 0 && now - stateChangeTime > autoHideTimeoutMs) {
				setThinking(false, label, autoHideTimeoutMs);
			}
			render(guiGraphics, now);
		});
		*/
		//?}
	}

	public static void setThinking(boolean isThinking, String customLabel, long timeoutMs) {
		String normalized = customLabel != null && !customLabel.isBlank() ? customLabel : "AI Thinking";
		boolean labelChanged = isThinking && !normalized.equals(label);
		if (thinking != isThinking || labelChanged) {
			stateChangeTime = System.currentTimeMillis();
		}
		thinking = isThinking;
		if (isThinking) {
			label = normalized;
			autoHideTimeoutMs = Math.min(Math.max(timeoutMs, 1000L), 300000L);
		}
	}

	public static boolean isThinking() {
		return thinking;
	}

	private static void render(GuiGraphics gfx, long now) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.player == null || mc.screen != null) return;

		long animElapsed = now - stateChangeTime;
		double animProgress = Math.min(1.0, animElapsed / 280.0); // 280ms transition

		// Slide-in / slide-out cubic easing
		double slideFactor;
		if (thinking) {
			// Ease-out cubic for smooth slide-in
			slideFactor = 1.0 - Math.pow(1.0 - animProgress, 3);
		} else {
			// Slide-out cubic
			slideFactor = Math.pow(1.0 - animProgress, 3);
			if (slideFactor <= 0.001) return; // Completely hidden
		}

		Font font = mc.font;
		int screenWidth = mc.getWindow().getGuiScaledWidth();

		// Static text with 3 static dots
		String text = label + " ...";
		int textWidth = font.width(text);
		int paddingX = 10;
		int paddingY = 6;
		int boxWidth = textWidth + paddingX * 2 + 12; // room for static icon/accent
		int boxHeight = font.lineHeight + paddingY * 2;

		// Calculate target X and slide offset from right
		int targetX = screenWidth - boxWidth - 12;
		int offscreenX = screenWidth + 10;
		int currentX = (int) (offscreenX - (offscreenX - targetX) * slideFactor);
		int currentY = 12;

		// Smooth breathing pulse effect for text brightness and glowing border
		double pulse = (Math.sin(now / 180.0) + 1.0) / 2.0; // 0.0 .. 1.0
		int alpha = (int) (slideFactor * 255);

		// Colors
		int bgColor = ((int) (slideFactor * 0xD8) << 24) | 0x0A0E17;
		int borderColor = (alpha << 24) | (((int) (120 + pulse * 135)) << 16) | (((int) (160 + pulse * 95)) << 8) | 0xFF;
		
		// Text pulsing color from subtle blue to bright white
		int textBrightness = (int) (180 + pulse * 75);
		int textColor = (alpha << 24) | (textBrightness << 16) | (Math.min(255, textBrightness + 25) << 8) | 0xFF;

		// 1. Draw glowing background pill
		gfx.fill(currentX, currentY, currentX + boxWidth, currentY + boxHeight, bgColor);
		gfx.renderOutline(currentX, currentY, boxWidth, boxHeight, borderColor);

		// 2. Static accent diamond / indicator
		int iconX = currentX + paddingX;
		int iconY = currentY + (boxHeight / 2) - 2;
		int accentColor = (alpha << 24) | 0x00E5FF;
		gfx.fill(iconX, iconY, iconX + 4, iconY + 4, accentColor);

		// 3. Draw pulsing text
		int textX = iconX + 8;
		int textY = currentY + paddingY;
		gfx.drawString(font, text, textX, textY, textColor, true);
	}
}