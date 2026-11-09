package io.github.soymods.clanker.test;

import io.github.soymods.clanker.config.ServerConfig;

/** Settings every test class starts from, so tests never reach a paid service unless they mean to. */
final class TestSetup {
	private TestSetup() {
	}

	static void isolate() {
		// The host voice would otherwise use a real key from ~/.clanker if one is there.
		ServerConfig.get().voiceProvider = ServerConfig.VoiceProvider.LOCAL;
	}
}
