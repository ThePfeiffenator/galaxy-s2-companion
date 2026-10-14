package org.retrobridge.galaxyservices.s2;
/** Exact service-record query for the already paired Pixel on Samsung's old stack. */
final class LegacyDiscovery {
 static {System.loadLibrary("galaxywifisdp");}
 static native int channel();
 private LegacyDiscovery(){}
}
