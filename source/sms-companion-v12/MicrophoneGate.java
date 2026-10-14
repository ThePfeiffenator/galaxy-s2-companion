package org.retrobridge.callprobe;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes mute acknowledgments with outgoing PCM frames, never their capture. */
final class MicrophoneGate {
    interface FrameWriter { int write(byte[] pcm, int count); }
    private final ReentrantLock lock = new ReentrantLock(true);
    private boolean muted;
    private volatile long generation;
    long generation() { return generation; }
    private void acquire() throws IOException {
        try {
            if(!lock.tryLock(1500,TimeUnit.MILLISECONDS)) throw new IOException("Microphone gate timed out");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Microphone gate interrupted");
        }
    }
    boolean setMuted(boolean value) throws IOException {
        acquire();
        try { boolean changed=muted!=value; muted=value; if(changed)generation++; return changed; }
        finally { lock.unlock(); }
    }
    int write(byte[] pcm,int count,FrameWriter writer) throws IOException {
        return write(pcm,count,generation,writer);
    }
    int write(byte[] pcm,int count,long capturedGeneration,FrameWriter writer) throws IOException {
        if(pcm==null || count<0 || count>pcm.length || writer==null) throw new IOException("Invalid microphone frame");
        acquire();
        try {
            // Never replay audio captured before a mute/unmute transition.
            if(muted || capturedGeneration!=generation) Arrays.fill(pcm,0,count,(byte)0);
            // The native writer has a one-second poll and a nonblocking send.
            // setMuted cannot acknowledge while this write is still in flight.
            return writer.write(pcm,count);
        } finally { lock.unlock(); }
    }
}
