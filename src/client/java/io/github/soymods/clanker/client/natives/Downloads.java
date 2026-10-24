package io.github.soymods.clanker.client.natives;

import io.github.soymods.clanker.Clanker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads voice files in the background, one job at a time. Only ever started by the player pressing a button.
 */
public final class Downloads {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(20))
			.build();
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Clanker Downloads");
		thread.setDaemon(true);
		return thread;
	});

	/** One file to fetch. {@code sha256} may be null for files with no published hash. */
	public record Item(String label, String url, Path target, long size, String sha256) {
	}

	public static final class Job {
		private final long total;
		private volatile long done;
		private volatile String current = "";
		private volatile String error;
		private volatile boolean finished;

		Job(long total) {
			this.total = total;
		}

		public double progress() {
			return total <= 0 ? 0 : Math.min(1, (double) done / total);
		}

		public String current() {
			return current;
		}

		/** Null unless the job failed. */
		public String error() {
			return error;
		}

		public boolean finished() {
			return finished;
		}
	}

	private static volatile Job job;

	private Downloads() {
	}

	/** The running or last finished job, or null. */
	public static Job job() {
		return job;
	}

	public static boolean running() {
		Job current = job;
		return current != null && !current.finished;
	}

	public static synchronized Job start(List<Item> items) {
		if (running()) {
			return job;
		}
		Job started = new Job(items.stream().mapToLong(Item::size).sum());
		job = started;
		EXECUTOR.execute(() -> {
			try {
				for (Item item : items) {
					started.current = item.label();
					fetch(item, started);
				}
			} catch (IOException | RuntimeException e) {
				Clanker.LOGGER.warn("Voice download failed", e);
				started.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			} catch (InterruptedException e) {
				started.error = "cancelled";
				Thread.currentThread().interrupt();
			} finally {
				started.finished = true;
			}
		});
		return started;
	}

	private static void fetch(Item item, Job job) throws IOException, InterruptedException {
		Files.createDirectories(item.target().getParent());
		Path part = item.target().resolveSibling(item.target().getFileName() + ".part");
		HttpRequest request = HttpRequest.newBuilder(URI.create(item.url()))
				.header("User-Agent", "Clanker (Minecraft mod)")
				.GET().build();
		HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			response.body().close();
			throw new IOException(item.label() + ": HTTP " + response.statusCode());
		}
		MessageDigest digest = sha256();
		long start = job.done;
		try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
			byte[] buffer = new byte[1 << 16];
			int read;
			long written = 0;
			while ((read = in.read(buffer)) >= 0) {
				out.write(buffer, 0, read);
				digest.update(buffer, 0, read);
				written += read;
				job.done = start + Math.min(written, item.size());
			}
		}
		String hash = HexFormat.of().formatHex(digest.digest());
		if (item.sha256() != null && !item.sha256().equalsIgnoreCase(hash)) {
			Files.deleteIfExists(part);
			throw new IOException(item.label() + " didn't match its expected checksum");
		}
		Files.move(part, item.target(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		job.done = start + item.size();
		Clanker.LOGGER.info("Downloaded {} to {}", item.label(), item.target());
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
