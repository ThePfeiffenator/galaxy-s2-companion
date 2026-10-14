package org.retrobridge.callprobe;

final class ReconnectPolicy {
    private boolean paused;
    private int failures;
    private long nextAttempt;
    synchronized void pause(boolean value) { paused=value; if(!value) { failures=0; nextAttempt=0; } }
    synchronized boolean paused() { return paused; }
    synchronized boolean allowed(boolean bluetoothOn,long now) { return !paused && bluetoothOn && now>=nextAttempt; }
    synchronized void connected() { failures=0; nextAttempt=0; }
    synchronized void failed(long now) {
        int shift=Math.min(failures++,4);
        nextAttempt=now+Math.min(30000,2000L<<shift);
    }
    synchronized void dueNow() { nextAttempt=0; }
}
