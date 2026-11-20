package cn.izis.izisbridge;

/** Pure full-grid decoding; never crops away an unauthorized outside stone. */
final class BoardPosition {
    static int[] decode(String frame) {
        if (frame == null || !frame.matches("~SDA[012]{361}#")) throw new IllegalArgumentException("Invalid full snapshot");
        int[] board = new int[361];
        for (int i = 0; i < 361; i++) board[360 - i] = frame.charAt(4 + i) - '0';
        return board;
    }
    static int fullPoint(int point, int size) {
        if ((size != 9 && size != 13 && size != 19) || point < 0 || point >= size * size)
            throw new IllegalArgumentException("Invalid logical point");
        int offset = (19 - size) / 2;
        return (point / size + offset) * 19 + point % size + offset;
    }
    static boolean[] boundary(int size) {
        fullPoint(0, size);
        boolean[] result = new boolean[361];
        if (size == 19) return result;
        int low = (19 - size) / 2 - 1, high = low + size + 1;
        for (int row = low; row <= high; row++) for (int col = low; col <= high; col++)
            if (row == low || row == high || col == low || col == high) result[row * 19 + col] = true;
        return result;
    }
}
