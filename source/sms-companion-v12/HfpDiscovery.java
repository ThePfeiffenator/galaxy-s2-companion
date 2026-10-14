package org.retrobridge.callprobe;
final class HfpDiscovery {
 static {System.loadLibrary("galaxyhfp");}
 static native int channel();
 static native int voiceMtu(int fd);
}
