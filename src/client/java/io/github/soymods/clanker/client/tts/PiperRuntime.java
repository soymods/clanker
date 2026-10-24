package io.github.soymods.clanker.client.tts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Piper (via piper-jni) loaded from the downloaded jar in its own class loader. The jar isn't on the mod's class path,
 * so the handful of calls Clanker needs go through reflection.
 */
final class PiperRuntime implements AutoCloseable {
	private final URLClassLoader loader;
	private final Object piper;
	private final Method loadVoice;
	private final Method textToAudio;
	private final Method sampleRate;
	private final Method closeVoice;
	private final Method terminate;

	private PiperRuntime(URLClassLoader loader, Object piper, Class<?> voiceClass) throws ReflectiveOperationException {
		this.loader = loader;
		this.piper = piper;
		Class<?> piperClass = piper.getClass();
		this.loadVoice = piperClass.getMethod("loadVoice", Path.class, Path.class);
		this.textToAudio = piperClass.getMethod("textToAudio", voiceClass, String.class);
		this.sampleRate = voiceClass.getMethod("getSampleRate");
		this.closeVoice = voiceClass.getMethod("close");
		this.terminate = piperClass.getMethod("terminate");
	}

	/** Loads the natives (piper-jni extracts them to a temp directory) and initializes eSpeak. */
	static PiperRuntime load(Path jar) throws Exception {
		URLClassLoader loader = new PiperClassLoader(jar.toUri().toURL());
		try {
			Class<?> piperClass = loader.loadClass("io.github.givimad.piperjni.PiperJNI");
			Class<?> voiceClass = loader.loadClass("io.github.givimad.piperjni.PiperVoice");
			Object piper = piperClass.getConstructor().newInstance();
			piperClass.getMethod("initialize").invoke(piper);
			return new PiperRuntime(loader, piper, voiceClass);
		} catch (InvocationTargetException e) {
			loader.close();
			throw unwrap(e);
		} catch (Exception e) {
			loader.close();
			throw e;
		}
	}

	Voice loadVoice(Path model, Path config) throws Exception {
		try {
			Object voice = loadVoice.invoke(piper, model, config);
			return new Voice(voice, (int) sampleRate.invoke(voice));
		} catch (InvocationTargetException e) {
			throw unwrap(e);
		}
	}

	short[] speak(Voice voice, String text) throws Exception {
		try {
			return (short[]) textToAudio.invoke(piper, voice.handle, text);
		} catch (InvocationTargetException e) {
			throw unwrap(e);
		}
	}

	void closeVoice(Voice voice) {
		try {
			closeVoice.invoke(voice.handle);
		} catch (ReflectiveOperationException ignored) {
		}
	}

	@Override
	public void close() throws Exception {
		terminate.invoke(piper);
		loader.close();
	}

	private static Exception unwrap(InvocationTargetException e) {
		return e.getCause() instanceof Exception cause ? cause : e;
	}

	record Voice(Object handle, int sampleRate) {
	}

	/**
	 * piper-jni 1.4.1's macOS {@code libonnxruntime.dylib} is a symlink that became a 27-byte text file in the jar, so
	 * loading it fails. Serve the real library under that name; libpiper-jni finds it by its install name
	 * ({@code @rpath/libonnxruntime.1.22.0.dylib}) once it's loaded.
	 */
	private static final class PiperClassLoader extends URLClassLoader {
		private static final String STUB = "libonnxruntime.dylib";
		private static final String REAL = "libonnxruntime.1.22.0.dylib";

		PiperClassLoader(URL jar) {
			super("clanker-piper", new URL[] {jar}, PiperRuntime.class.getClassLoader());
		}

		@Override
		public URL getResource(String name) {
			String path = name.startsWith("/") ? name.substring(1) : name;
			if (path.startsWith("macos-") && path.endsWith("/" + STUB)) {
				return findResource(path.substring(0, path.length() - STUB.length()) + REAL);
			}
			return super.getResource(name);
		}
	}
}
