package org.retrobridge.callprobe;

/** Bounded PCM jitter buffer. Each complete packet retains its mute generation. */
final class MicrophoneBuffer {
    static final long EMPTY = Long.MIN_VALUE;
    private final byte[][] frames;
    private final long[] generations;
    private final int target;
    private int head, size;
    private long dropped;
    private boolean primed;
    MicrophoneBuffer(int bytes, int targetFrames, int capacityFrames) {
        if(bytes<=0 || (bytes&1)!=0 || targetFrames<1 || capacityFrames<targetFrames)
            throw new IllegalArgumentException("PCM buffer dimensions");
        frames=new byte[capacityFrames][bytes]; generations=new long[capacityFrames]; target=targetFrames;
    }
    synchronized void offer(byte[] pcm, long generation) {
        if(pcm.length!=frames[0].length) throw new IllegalArgumentException("PCM packet size");
        // A prolonged transport stall must not make conversation delay grow forever.
        if(size==frames.length) { head=(head+1)%frames.length;size--;dropped++; }
        int tail=(head+size)%frames.length;
        System.arraycopy(pcm,0,frames[tail],0,pcm.length);generations[tail]=generation;size++;
        if(size>=target)primed=true;
        notifyAll();
    }
    synchronized boolean awaitPrimed() throws InterruptedException {
        if(!primed)wait(20);
        return primed;
    }
    synchronized long take(byte[] pcm) {
        if(pcm.length!=frames[0].length)throw new IllegalArgumentException("PCM packet size");
        if(!primed || size==0)return EMPTY;
        System.arraycopy(frames[head],0,pcm,0,pcm.length);
        long epoch=generations[head];head=(head+1)%frames.length;size--;return epoch;
    }
    synchronized int depth(){return size;}
    synchronized long dropped(){return dropped;}
}
