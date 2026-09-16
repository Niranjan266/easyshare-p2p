package easyshare.peer;

import java.util.BitSet;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides which piece to request next from a peer, shared by all download threads.
 * Uses BitTorrent's "rarest first" strategy: among the pieces a peer can give us,
 * pick one that the fewest peers have, so rare pieces spread through the swarm quickly.
 */
final class PieceManager {
    private final int count;
    private final BitSet done;
    private final BitSet inProgress = new BitSet();
    private final int[] availability;

    PieceManager(BitSet alreadyHave, int count) {
        this.count = count;
        this.done = (BitSet) alreadyHave.clone();
        this.availability = new int[count];
    }

    synchronized void addAvailability(BitSet peerHas) {
        for (int i = peerHas.nextSetBit(0); i >= 0 && i < count; i = peerHas.nextSetBit(i + 1)) {
            availability[i]++;
        }
    }

    synchronized void removeAvailability(BitSet peerHas) {
        for (int i = peerHas.nextSetBit(0); i >= 0 && i < count; i = peerHas.nextSetBit(i + 1)) {
            availability[i]--;
        }
    }

    /** Reserves and returns the rarest missing piece this peer has, or -1 if the peer has nothing we need. */
    synchronized int pick(BitSet peerHas) {
        int best = -1;
        int ties = 0;
        for (int i = peerHas.nextSetBit(0); i >= 0 && i < count; i = peerHas.nextSetBit(i + 1)) {
            if (done.get(i) || inProgress.get(i)) {
                continue;
            }
            if (best < 0 || availability[i] < availability[best]) {
                best = i;
                ties = 1;
            } else if (availability[i] == availability[best] && ThreadLocalRandom.current().nextInt(++ties) == 0) {
                best = i; // random choice among equally rare pieces spreads requests across peers
            }
        }
        if (best >= 0) {
            inProgress.set(best);
        }
        return best;
    }

    synchronized void completed(int index) {
        done.set(index);
        inProgress.clear(index);
    }

    synchronized void release(int index) {
        inProgress.clear(index);
    }

    synchronized boolean allDone() {
        return done.cardinality() == count;
    }

    synchronized int doneCount() {
        return done.cardinality();
    }
}
