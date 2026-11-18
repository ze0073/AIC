package com.shijie.accessibility.common;

import java.security.SecureRandom;

public final class Ulids {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulids() {}

    public static String next() {
        byte[] value = new byte[16];
        long now = System.currentTimeMillis();
        value[0] = (byte) (now >>> 40);
        value[1] = (byte) (now >>> 32);
        value[2] = (byte) (now >>> 24);
        value[3] = (byte) (now >>> 16);
        value[4] = (byte) (now >>> 8);
        value[5] = (byte) now;
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        System.arraycopy(random, 0, value, 6, random.length);
        StringBuilder out = new StringBuilder(26);
        int buffer = 0;
        int bits = 0;
        for (byte b : value) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(ALPHABET[(buffer >>> bits) & 31]);
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer << (5 - bits)) & 31]);
        while (out.length() < 26) out.append('0');
        return out.substring(0, 26);
    }
}

