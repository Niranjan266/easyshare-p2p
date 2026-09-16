package easyshare.peer;

import easyshare.common.PeerAddress;
import easyshare.common.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Download side of the peer-wire protocol: one TCP connection to one remote peer. */
public final class PeerClient implements Closeable {
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final String remoteId;
    private final String remoteName;

    public record RemoteFile(String infoHash, String name, long length, int pieceCount, int haveCount) {
    }

    private PeerClient(Socket socket, DataInputStream in, DataOutputStream out, String remoteId, String remoteName) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.remoteId = remoteId;
        this.remoteName = remoteName;
    }

    public static PeerClient connect(PeerAddress address, String myId, String myName, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address.host(), address.port()), timeoutMs);
            socket.setSoTimeout(60_000);
            socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
            out.writeInt(Protocol.MAGIC);
            Protocol.writeString(out, myId);
            Protocol.writeString(out, myName);
            out.flush();
            if (in.readInt() != Protocol.MAGIC) {
                throw new IOException("Not an EasyShare peer");
            }
            String id = Protocol.readString(in);
            String name = Protocol.readString(in);
            return new PeerClient(socket, in, out, id, name);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    public String remoteId() {
        return remoteId;
    }

    public String remoteName() {
        return remoteName;
    }

    public List<RemoteFile> listFiles() throws IOException {
        out.writeByte(Protocol.CMD_LIST);
        out.flush();
        int count = in.readInt();
        if (count < 0 || count > 100_000) {
            throw new IOException("Invalid file count");
        }
        List<RemoteFile> files = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            files.add(new RemoteFile(Protocol.readString(in), Protocol.readString(in), in.readLong(), in.readInt(), in.readInt()));
        }
        return files;
    }

    /** Returns the metadata text, or null if the peer does not have the file. */
    public String getMeta(String infoHash) throws IOException {
        out.writeByte(Protocol.CMD_META);
        Protocol.writeString(out, infoHash);
        out.flush();
        return okStringOrNull();
    }

    /** Selects a file on the remote peer and returns which pieces it has, or null if it does not have the file. */
    public BitSet handshake(String infoHash, int pieceCount) throws IOException {
        out.writeByte(Protocol.CMD_HANDSHAKE);
        Protocol.writeString(out, infoHash);
        out.flush();
        byte response = in.readByte();
        if (response == Protocol.RESP_ERROR) {
            Protocol.readString(in);
            return null;
        }
        expect(response, Protocol.RESP_OK);
        return Protocol.readBitfield(in, pieceCount);
    }

    public BitSet bitfield(int pieceCount) throws IOException {
        out.writeByte(Protocol.CMD_BITFIELD);
        out.flush();
        byte response = in.readByte();
        if (response == Protocol.RESP_ERROR) {
            throw new IOException(Protocol.readString(in));
        }
        expect(response, Protocol.RESP_OK);
        return Protocol.readBitfield(in, pieceCount);
    }

    /** Downloads one piece. Returns null if the peer no longer has it. The caller verifies the SHA-1. */
    public byte[] requestPiece(int index, int expectedLength) throws IOException {
        out.writeByte(Protocol.CMD_REQUEST);
        out.writeInt(index);
        out.flush();
        byte response = in.readByte();
        if (response == Protocol.RESP_NO_PIECE) {
            in.readInt();
            return null;
        }
        if (response == Protocol.RESP_ERROR) {
            throw new IOException(Protocol.readString(in));
        }
        expect(response, Protocol.RESP_PIECE);
        int receivedIndex = in.readInt();
        int length = in.readInt();
        if (receivedIndex != index || length != expectedLength) {
            throw new IOException("Peer sent the wrong piece");
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    private String okStringOrNull() throws IOException {
        byte response = in.readByte();
        if (response == Protocol.RESP_ERROR) {
            Protocol.readString(in);
            return null;
        }
        expect(response, Protocol.RESP_OK);
        return Protocol.readString(in);
    }

    private static void expect(byte actual, byte expected) throws IOException {
        if (actual != expected) {
            throw new IOException("Unexpected response " + actual);
        }
    }

    /** Closes the socket immediately (used from another thread to stop a transfer). */
    public void abort() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    @Override
    public void close() {
        try {
            out.writeByte(Protocol.CMD_BYE);
            out.flush();
        } catch (IOException ignored) {
            // already closed
        }
        abort();
    }
}
