package io.descoped.tuple;

/** Readable text for byte arrays in {@code toString} output. */
final class ByteFormat {

    private ByteFormat() {
    }

    /** Printable ASCII as is, anything else as {@code \xNN}. Backslash and double quote are escaped too. */
    static String printable(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int value = b & 0xff;
            if (value == '\\' || value == '"') {
                text.append('\\').append((char) value);
            } else if (value >= 0x20 && value < 0x7f) {
                text.append((char) value);
            } else {
                text.append(String.format("\\x%02x", value));
            }
        }
        return text.toString();
    }
}
