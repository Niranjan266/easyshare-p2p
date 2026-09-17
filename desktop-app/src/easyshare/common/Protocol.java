package easyshare.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/**
 * Binary peer-wire protocol (peer <-> peer over TCP).
 *
 * <pre>
 * Connection opening (both directions):  int MAGIC, string peerId, string peerName
 *
 * Commands (1 byte) and their replies:
 *   LIST                     -> int n, then n x (string infoHash, string name, long size, int pieces, int havePieces)
 *   META      string hash    -> OK string metadataText | ERROR string
 *   HANDSHAKE string hash    -> OK bitfield            | ERROR string   (selects the file for REQUESTs)
 *   BITFIELD                 -> OK bitfield            | ERROR string   (refresh which pieces the peer has)
 *   REQUEST   int index      -> PIECE int index, int length, bytes | NO_PIECE int index | ERROR string
 *   BYE                      -> (connection closed)
 *
 * string   = int length + UTF-8 bytes
 * bitfield = int pieceCount + ceil(pieceCount/8) bytes, most significant bit first (like BitTorrent)
 * </pre>
 */
public final class Protocol {
    public static final int MAGIC = 0x45535031; // "ESP1"

    public static final byte CMD_LIST = 1;
    public static final byte CMD_META = 2;
    public static final byte CMD_HANDSHAKE = 3;
    public static final byte CMD_BITFIELD = 4;
    public static final byte CMD_REQUEST = 5;
    public static final byte CMD_BYE = 6;

    public static final byte RESP_OK = 10;
    public static final byte RESP_ERROR = 11;
    public static final byte RESP_PIECE = 12;
    public static final byte RESP_NO_PIECE = 13;

    public static final int MAX_STRING = 16 * 1024 * 1024;

    private Protocol() {
    }

    public static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING) {
            throw new IOException("Invalid string length " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeBitfield(DataOutputStream out, BitSet bits, int count) throws IOException {
        byte[] bytes = new byte[(count + 7) / 8];
        for (int i = bits.nextSetBit(0); i >= 0 && i < count; i = bits.nextSetBit(i + 1)) {
            bytes[i / 8] |= (byte) (1 << (7 - i % 8));
        }
        out.writeInt(count);
        out.write(bytes);
    }

    public static BitSet readBitfield(DataInputStream in, int expectedCount) throws IOException {
        int count = in.readInt();
        if (count != expectedCount) {
            throw new IOException("Bitfield has " + count + " pieces, expected " + expectedCount);
        }
        byte[] bytes = new byte[(count + 7) / 8];
        in.readFully(bytes);
        BitSet bits = new BitSet(count);
        for (int i = 0; i < count; i++) {
            if ((bytes[i / 8] & (1 << (7 - i % 8))) != 0) {
                bits.set(i);
            }
        }
        return bits;
    }
}
