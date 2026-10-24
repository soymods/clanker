package io.github.soymods.clanker.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.soymods.clanker.Clanker;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class ClankerKeys {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Clanker.id("clanker"));

	public static KeyMapping pushToTalk;
	public static KeyMapping openSettings;
	public static KeyMapping stop;

	private ClankerKeys() {
	}

	public static void register() {
		pushToTalk = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.clanker.push_to_talk", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));
		openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.clanker.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
		stop = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.clanker.stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
	}
}
