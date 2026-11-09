package dev.panelume.reader;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

final class Bookmark {
    // Two alternating records let a new process recover the previous frame if
    // it opens the file after a write was interrupted by process death.
    private static final int SLOT = 40;
    private static final int SIZE = SLOT * 2;
    private static final long MAGIC = 0x50616e656c756d65L;

    private final MappedByteBuffer memory;
    private Position current;

    Bookmark(File file) throws IOException {
        try (RandomAccessFile data = new RandomAccessFile(file, "rw")) {
            if (data.length() != SIZE) data.setLength(SIZE);
            memory = data.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, SIZE);
            current = latest(memory);
        }
    }

    static Position read(File file) {
        if (!file.isFile()) return Position.EMPTY;
        try (RandomAccessFile data = new RandomAccessFile(file, "r")) {
            if (data.length() != SIZE) return Position.EMPTY;
            byte[] content = new byte[SIZE];
            data.readFully(content);
            return latest(ByteBuffer.wrap(content));
        } catch (IOException e) {
            return Position.EMPTY;
        }
    }

    Position get() {
        return current;
    }

    void save(int page, double fraction, int percent, double frontier) {
        if (current.page == page && current.fraction == fraction
            && current.percent == percent && current.frontier == frontier) return;
        long sequence = current.sequence + 1;
        int offset = (int) (sequence & 1) * SLOT;
        memory.putLong(offset, 0);
        memory.putInt(offset + 8, page);
        memory.putInt(offset + 12, percent);
        memory.putDouble(offset + 16, fraction);
        memory.putDouble(offset + 24, frontier);
        memory.putLong(offset + 32, checksum(sequence, page, percent, fraction, frontier));
        memory.putLong(offset, sequence);
        current = new Position(sequence, page, fraction, percent, frontier);
    }

    void flush() {
        memory.force();
    }

    private static Position latest(ByteBuffer data) {
        Position first = readSlot(data, 0);
        Position second = readSlot(data, SLOT);
        return first.sequence > second.sequence ? first : second;
    }

    private static Position readSlot(ByteBuffer data, int offset) {
        long sequence = data.getLong(offset);
        int page = data.getInt(offset + 8);
        int percent = data.getInt(offset + 12);
        double fraction = data.getDouble(offset + 16);
        double frontier = data.getDouble(offset + 24);
        long check = data.getLong(offset + 32);
        if (sequence <= 0 || page < 0 || percent < 0 || percent > 100 || !(fraction >= 0 && fraction <= 1)
            || !(frontier >= 0) || !Double.isFinite(frontier)
            || check != checksum(sequence, page, percent, fraction, frontier)) return Position.EMPTY;
        return new Position(sequence, page, fraction, percent, frontier);
    }

    private static long checksum(long sequence, int page, int percent, double fraction, double frontier) {
        return MAGIC ^ Long.rotateLeft(sequence, 13) ^ ((long) page << 32) ^ percent
            ^ Long.rotateLeft(Double.doubleToLongBits(fraction), 29) ^ Double.doubleToLongBits(frontier);
    }

    static final class Position {
        static final Position EMPTY = new Position(0, 0, 0, 0, 0);
        final long sequence;
        final int page;
        final double fraction;
        final int percent;
        final double frontier;

        Position(long sequence, int page, double fraction, int percent, double frontier) {
            this.sequence = sequence;
            this.page = page;
            this.fraction = fraction;
            this.percent = percent;
            this.frontier = frontier;
        }
    }
}
