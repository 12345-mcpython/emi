package dev.emi.emi.platform;

/**
 * Dist safe client state. Packets live in the common jar and are loaded on a dedicated server, so they must
 * not reference {@link EmiClient}, which pulls in client only classes.
 */
public class EmiClientState {
	public static boolean onServer = false;
}
