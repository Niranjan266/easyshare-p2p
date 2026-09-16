package easyshare.peer;

import easyshare.common.DaemonThreads;
import easyshare.common.Format;
import easyshare.common.Log;
import easyshare.common.Protocol;
import easyshare.common.RateLimiter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Upload side of a peer. Listens on a TCP port and serves pieces to other peers.
 * Every connection is handled by its own thread from a thread pool, so many peers
 * can download from this peer at the same time.
 */
public final class PeerServer {
    private final PeerNode node;
    private final RateLimiter limiter;
    private final ExecutorService pool = Executors.newCachedThreadPool(DaemonThreads.named("upload"));
    private final Random random = new Random();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private ServerSocket serverSocket;
    private volatile boolean running;

    PeerServer(PeerNode node, RateLimiter limiter) {
        this.node = node;
        this.limiter = limiter;
    }

    void start(int port) throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(port), 50);
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "peer-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // closing
        }
        pool.shutdownNow();
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                pool.submit(() -> handle(socket));
            } catch (IOException e) {
                if (running) {
                    Log.info("UPLOAD", "Accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handle(Socket socket) {
        String remoteName = socket.getInetAddress().getHostAddress();
        SharedFile current = null;
        int piecesSent = 0;
        long bytesSent = 0;
        activeConnections.incrementAndGet();
        try (socket) {
            socket.setSoTimeout(5 * 60_000);
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));

            if (in.readInt() != Protocol.MAGIC) {
                return;
            }
            Protocol.readString(in); // remote peer id
            remoteName = Protocol.readString(in) + " (" + socket.getInetAddress().getHostAddress() + ")";
            out.writeInt(Protocol.MAGIC);
            Protocol.writeString(out, node.peerId());
            Protocol.writeString(out, node.config().name);
            out.flush();

            while (running) {
                byte command = in.readByte();
                if (command == Protocol.CMD_LIST) {
                    List<SharedFile> files = node.registry().list();
                    out.writeInt(files.size());
                    for (SharedFile f : files) {
                        Protocol.writeString(out, f.meta().infoHash());
                        Protocol.writeString(out, f.meta().name());
                        out.writeLong(f.meta().length());
                        out.writeInt(f.meta().pieceCount());
                        out.writeInt(f.haveCount());
                    }
                } else if (command == Protocol.CMD_META) {
                    SharedFile f = node.registry().get(Protocol.readString(in));
                    if (f == null) {
                        error(out, "File not shared by this peer");
                    } else {
                        out.writeByte(Protocol.RESP_OK);
                        Protocol.writeString(out, f.meta().text());
                    }
                } else if (command == Protocol.CMD_HANDSHAKE) {
                    SharedFile f = node.registry().get(Protocol.readString(in));
                    if (f == null) {
                        error(out, "File not shared by this peer");
                    } else {
                        if (current != f) {
                            logUploadSummary(current, piecesSent, bytesSent, remoteName);
                            piecesSent = 0;
                            bytesSent = 0;
                            Log.info("UPLOAD", remoteName + " connected to download " + f.meta().name()
                                    + " (we have " + f.haveCount() + "/" + f.meta().pieceCount() + " pieces)");
                        }
                        current = f;
                        out.writeByte(Protocol.RESP_OK);
                        Protocol.writeBitfield(out, f.bitfield(), f.meta().pieceCount());
                    }
                } else if (command == Protocol.CMD_BITFIELD) {
                    if (current == null) {
                        error(out, "No file selected");
                    } else {
                        out.writeByte(Protocol.RESP_OK);
                        Protocol.writeBitfield(out, current.bitfield(), current.meta().pieceCount());
                    }
                } else if (command == Protocol.CMD_REQUEST) {
                    int index = in.readInt();
                    if (current == null || index < 0 || index >= current.meta().pieceCount()) {
                        error(out, "Invalid piece request");
                    } else {
                        byte[] data = current.readPiece(index);
                        if (data == null) {
                            out.writeByte(Protocol.RESP_NO_PIECE);
                            out.writeInt(index);
                        } else {
                            data = maybeCorrupt(data, index, current, remoteName);
                            sendPiece(out, index, data);
                            piecesSent++;
                            bytesSent += data.length;
                        }
                    }
                } else {
                    return; // BYE or unknown command
                }
                out.flush();
            }
        } catch (EOFException | SocketException e) {
            // the other peer closed the connection
        } catch (IOException e) {
            Log.info("UPLOAD", "Connection with " + remoteName + " ended: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            activeConnections.decrementAndGet();
            logUploadSummary(current, piecesSent, bytesSent, remoteName);
        }
    }

    private void sendPiece(DataOutputStream out, int index, byte[] data) throws IOException, InterruptedException {
        out.writeByte(Protocol.RESP_PIECE);
        out.writeInt(index);
        out.writeInt(data.length);
        int offset = 0;
        while (offset < data.length) {
            int n = Math.min(16 * 1024, data.length - offset);
            limiter.acquire(n);
            out.write(data, offset, n);
            if (limiter.isLimited()) {
                out.flush();
            }
            offset += n;
            node.stats().addUploaded(n);
        }
    }

    /** Demonstration of integrity checking: deliberately damage some pieces when --corrupt is set. */
    private byte[] maybeCorrupt(byte[] data, int index, SharedFile file, String remoteName) {
        int percent = node.config().corruptPercent;
        if (percent <= 0 || random.nextInt(100) >= percent) {
            return data;
        }
        byte[] copy = data.clone();
        copy[random.nextInt(copy.length)] ^= (byte) 0xFF;
        Log.info("SIMULATION", "Sending CORRUPTED piece #" + index + " of " + file.meta().name() + " to " + remoteName);
        return copy;
    }

    private static void error(DataOutputStream out, String message) throws IOException {
        out.writeByte(Protocol.RESP_ERROR);
        Protocol.writeString(out, message);
    }

    private static void logUploadSummary(SharedFile file, int pieces, long bytes, String remoteName) {
        if (file != null && pieces > 0) {
            Log.info("UPLOAD", "Sent " + pieces + " pieces (" + Format.bytes(bytes) + ") of " + file.meta().name() + " to " + remoteName);
        }
    }
}
