package io.github.soymods.clanker.server.brain;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.server.brain.llm.LlmResponse;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * Estimated API spend for this server session and today, checked against the host's caps. Prices are USD per
 * million tokens; unknown models use {@link #FALLBACK} (deliberately pricey) unless the host sets custom prices.
 * Today's total is kept in {@code config/clanker-spend.json} so restarting doesn't reset the daily cap.
 */
public final class Spend {
	public record Price(double input, double cachedInput, double output) {
	}

	/** Known list prices; check these against the provider's pricing page when adding models. */
	private static final Map<String, Price> PRICES = Map.ofEntries(
			Map.entry("gpt-5", new Price(1.25, 0.125, 10)),
			Map.entry("gpt-5-mini", new Price(0.25, 0.025, 2)),
			Map.entry("gpt-5-nano", new Price(0.05, 0.005, 0.4)),
			Map.entry("gpt-4.1", new Price(2, 0.5, 8)),
			Map.entry("gpt-4.1-mini", new Price(0.4, 0.1, 1.6)),
			Map.entry("gpt-4.1-nano", new Price(0.1, 0.025, 0.4)),
			Map.entry("gpt-4o", new Price(2.5, 1.25, 10)),
			Map.entry("gpt-4o-mini", new Price(0.15, 0.075, 0.6)),
			Map.entry("o4-mini", new Price(1.1, 0.275, 4.4)),
			Map.entry("o3", new Price(2, 0.5, 8)));
	static final Price FALLBACK = new Price(2.5, 0.25, 15);
	private static final Gson GSON = new Gson();

	private static double session;
	private static double today;
	private static LocalDate day;

	private Spend() {
	}

	/** Price for the current config, and whether it's a guess. */
	public static Price price(ServerConfig config, String model) {
		if (config.priceInputPerMillion > 0 || config.priceOutputPerMillion > 0) {
			return new Price(config.priceInputPerMillion, config.priceInputPerMillion, config.priceOutputPerMillion);
		}
		if (config.provider == ServerConfig.Provider.OPENAI_COMPATIBLE) {
			return new Price(0, 0, 0); // local servers are free; hosted ones can set prices in the config
		}
		String key = model.toLowerCase(Locale.ROOT);
		Price price = PRICES.get(key);
		if (price == null) {
			// dated snapshots like gpt-5-mini-2025-08-07 cost the same as their alias
			price = PRICES.get(key.replaceAll("-\\d{4}-\\d{2}-\\d{2}$", ""));
		}
		return price == null ? FALLBACK : price;
	}

	public static boolean isKnown(ServerConfig config, String model) {
		return price(config, model) != FALLBACK;
	}

	public static double cost(Price price, LlmResponse.Usage usage) {
		long cached = Math.min(usage.cachedInputTokens(), usage.inputTokens());
		return ((usage.inputTokens() - cached) * price.input() + cached * price.cachedInput() + usage.outputTokens() * price.output()) / 1_000_000.0;
	}

	public static synchronized void record(double usd) {
		rollDay();
		session += usd;
		today += usd;
		save();
	}

	public static synchronized double session() {
		return session;
	}

	public static synchronized double today() {
		rollDay();
		return today;
	}

	/** Which cap is reached, or null. 0 means no cap. */
	public static synchronized String capReached(ServerConfig config) {
		rollDay();
		if (config.sessionSpendCap > 0 && session >= config.sessionSpendCap) {
			return String.format(Locale.ROOT, "the session spend cap ($%.2f)", config.sessionSpendCap);
		}
		if (config.dailySpendCap > 0 && today >= config.dailySpendCap) {
			return String.format(Locale.ROOT, "the daily spend cap ($%.2f)", config.dailySpendCap);
		}
		return null;
	}

	/** Starts a new session (server start). */
	public static synchronized void resetSession() {
		session = 0;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("clanker-spend.json");
	}

	private static void rollDay() {
		LocalDate now = LocalDate.now();
		if (day == null) {
			day = now;
			load();
		}
		if (!now.equals(day)) {
			day = now;
			today = 0;
		}
	}

	private static void load() {
		try {
			if (Files.isRegularFile(path())) {
				JsonObject json = GSON.fromJson(Files.readString(path()), JsonObject.class);
				if (json != null && day.toString().equals(json.get("date").getAsString())) {
					today = json.get("usd").getAsDouble();
				}
			}
		} catch (IOException | RuntimeException e) {
			Clanker.LOGGER.warn("Couldn't read {}", path(), e);
		}
	}

	private static void save() {
		JsonObject json = new JsonObject();
		json.addProperty("date", day.toString());
		json.addProperty("usd", today);
		try {
			Files.writeString(path(), GSON.toJson(json));
		} catch (IOException e) {
			Clanker.LOGGER.warn("Couldn't save {}", path(), e);
		}
	}
}
