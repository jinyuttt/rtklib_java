package org.rtklib.java.common;

/**
 * Bit manipulation utility functions.
 * Aligned with RTKLIB getbitu/getbits/setbitu/setbits.
 */
public final class BitUtils {
    private BitUtils() {
        // Utility class
    }

    /**
     * Extract unsigned bits from buffer.
     * @param buff Source buffer
     * @param pos  Bit position from the start of buffer
     * @param len  Bit length to extract (1..32)
     * @return Unsigned value of the extracted bits
     */
    public static long getbitu(byte[] buff, int pos, int len) {
        long bits = 0;
        for (int i = pos; i < pos + len; i++) {
            bits = (bits << 1) + ((buff[i / 8] >> (7 - (i % 8))) & 1);
        }
        return bits;
    }

    /**
     * Extract signed bits from buffer.
     * @param buff Source buffer
     * @param pos  Bit position from the start of buffer
     * @param len  Bit length to extract (1..32)
     * @return Signed value of the extracted bits
     */
    public static int getbits(byte[] buff, int pos, int len) {
        long bits = getbitu(buff, pos, len);
        if ((bits & (1L << (len - 1))) != 0) {
            bits |= (-1L << len);
        }
        return (int) bits;
    }

    /**
     * Set unsigned bits in buffer.
     * @param buff Destination buffer
     * @param pos  Bit position from the start of buffer
     * @param len  Bit length to set
     * @param data Unsigned value to set
     */
    public static void setbitu(byte[] buff, int pos, int len, long data) {
        long mask = 1L << (len - 1);
        int i;
        for (i = pos; i < pos + len; i++, mask >>>= 1) {
            if ((data & mask) != 0) {
                buff[i / 8] |= (byte) (1 << (7 - (i % 8)));
            } else {
                buff[i / 8] &= (byte) ~(1 << (7 - (i % 8)));
            }
        }
    }

    /**
     * Set signed bits in buffer.
     * @param buff Destination buffer
     * @param pos  Bit position from the start of buffer
     * @param len  Bit length to set
     * @param data Signed value to set
     */
    public static void setbits(byte[] buff, int pos, int len, int data) {
        if (data < 0) {
            data |= (1 << (len - 1));
        } else {
            data &= ~(1 << (len - 1));
        }
        setbitu(buff, pos, len, data & 0xFFFFFFFFL);
    }
}