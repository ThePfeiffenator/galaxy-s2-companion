package org.retrobridge.galaxychat.s2;
/** Query this app's exact UUID when Samsung's cached CreateDevice lookup stalls. */
final class LegacyDiscovery {
 static {System.loadLibrary("galaxysdp");}
 static native int channel();
 private LegacyDiscovery(){}
}
