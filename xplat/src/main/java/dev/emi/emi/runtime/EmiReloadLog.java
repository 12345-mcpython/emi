package dev.emi.emi.runtime;

import java.util.List;

import com.google.common.collect.Lists;

public class EmiReloadLog {
	private static List<String> pendingWarnings = Lists.newArrayList();
	public static List<String> warnings = List.of();
	private static int pendingWarningCount;
	public static int warningCount;

	public static void bake() {
		warnings = pendingWarnings;
		warningCount = pendingWarningCount;
		pendingWarnings = Lists.newArrayList();
		pendingWarningCount = 0;
	}

	public static void warn(String warning) {
		pendingWarnings.add(warning);
		EmiLog.error(warning);
		pendingWarningCount++;
	}

	public static void warn(String warning, Throwable t) {
		String detail = warning + " (" + describe(t) + ")";
		pendingWarnings.add(detail);
		EmiLog.error(detail, t);
		pendingWarningCount++;
	}

	/** Compact origin summary so the first frames survive log viewers that drop stack traces. */
	public static String describe(Throwable t) {
		StringBuilder sb = new StringBuilder(String.valueOf(t));
		StackTraceElement[] frames = t.getStackTrace();
		for (int i = 0; i < frames.length && i < 6; i++) {
			sb.append(" <- ").append(frames[i]);
		}
		return sb.toString();
	}

	public static void info(String info) {
		pendingWarnings.add(info);
		EmiLog.info(info);
	}
}
