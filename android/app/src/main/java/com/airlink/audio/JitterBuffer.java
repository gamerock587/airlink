package com.airlink.audio;

import java.util.TreeMap;

// Shared by receiver and audio threads. The queue has a hard bound; old sound
// is discarded on congestion so a Wi-Fi stall cannot become lasting delay.
public final class JitterBuffer {
    private final TreeMap<Long, byte[]> queue = new TreeMap<>();
    private final int target;
    private long next = -1;
    private boolean primed;
    public long missing, dropped, received;
    private int consecutiveGaps;
    public JitterBuffer(int target) { this.target = target; }
    public synchronized boolean offer(long sequence, byte[] pcm) {
        if (sequence < 0 || pcm.length != Protocol.PCM_BYTES || (next >= 0 && sequence < next) || queue.containsKey(sequence)) return false;
        // Once rebuffering after a stall, a valid authenticated future packet
        // must be allowed to establish the new live position.
        if (primed && next >= 0 && sequence > next + 2000) return false;
        received++;
        queue.put(sequence,pcm);
        if(queue.size() > target + 12) {
            while(queue.size() > target) { queue.pollFirstEntry(); dropped++; }
            next = queue.firstKey();
        }
        notifyAll(); return true;
    }
    public synchronized byte[] take() throws InterruptedException {
        if (!primed && queue.size() < target) { wait(100); if(queue.size()<target) return null; }
        if (!primed) { next = queue.firstKey(); primed = true; }
        byte[] data = queue.remove(next++);
        if (data == null) { missing++; if(++consecutiveGaps>=10) { primed=false; consecutiveGaps=0; } return new byte[Protocol.PCM_BYTES]; }
        consecutiveGaps=0;
        return data;
    }
    public synchronized void clear() { queue.clear(); next=-1; primed=false; consecutiveGaps=0; missing=0; dropped=0; received=0; notifyAll(); }
    public synchronized void rebuffer() { queue.clear(); primed=false; consecutiveGaps=0; notifyAll(); }
    public synchronized int size() { return queue.size(); }
    public synchronized String stats() { return "Received " + received + " packets · gaps " + missing + " · trimmed " + dropped; }
}
