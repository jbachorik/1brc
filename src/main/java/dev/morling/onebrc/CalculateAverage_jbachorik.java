/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dev.morling.onebrc;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class CalculateAverage_jbachorik {
    private static class Stats {
        long min;
        long max;
        long count;
        long sum;

        Stats() {
            min = Integer.MAX_VALUE;
            max = Integer.MIN_VALUE;
            count = 0;
            sum = 0;
        }

        Stats add(long value) {
            min = Math.min(min, value);
            max = Math.max(max, value);
            count++;
            sum += value;
            return this;
        }

        Stats merge(Stats other) {
            synchronized (this) {
                min = Math.min(min, other.min);
                max = Math.max(max, other.max);
                count += other.count;
                sum += other.sum;
            }
            return this;
        }

        @Override
        public String toString() {
            return String.format("%.1f/%.1f/%.1f", min / 10.0d, sum / (double) count / 10.0d, max / 10.0d);
        }
    }

    private static class StatsMap {
        private static class StatsHolder {
            private final ByteBuffer slice;
            private final Stats stats;

            StatsHolder(ByteBuffer slice, Stats stats) {
                this.slice = slice;
                this.stats = stats;
            }
        }

        private static final int BUCKETS = 1264532;
        private static final int BUCKET_SIZE = 4;
        private final StatsHolder[][] map = new StatsHolder[BUCKETS][BUCKET_SIZE];

        public Stats getOrInsert(ByteBuffer buffer, int len) {
            int idx = bucketIndex(buffer, len);
            ByteBuffer slice = buffer.slice(buffer.position() - len, len);
            StatsHolder[] bucket = map[idx];
            if (bucket[0] == null) {
                Stats stats = new Stats();
                bucket[0] = new StatsHolder(slice, stats);
                return stats;
            }
            int offset = 0;
            while (offset < BUCKET_SIZE && bucket[offset] != null && !equals(bucket[offset].slice, slice)) {
                offset++;
            }
            assert (offset <= BUCKET_SIZE);
            if (bucket[offset] != null) {
                return bucket[offset].stats;
            }
            else {
                Stats stats = new Stats();
                bucket[offset] = new StatsHolder(slice, stats);
                return stats;
            }
        }

        private static boolean equals(ByteBuffer leftSlice, ByteBuffer rightSlice) {
            int pos = leftSlice.position();

            int limit = leftSlice.limit();
            if (limit != rightSlice.limit()) {
                return false;
            }

            try {
                int i = 0;
                for (; i + 7 < limit; i += 8) {
                    long l = leftSlice.getLong();
                    long r = rightSlice.getLong();
                    if (l != r) {
                        return false;
                    }
                    pos += 8;
                }
                // else if (pos > 0) {
                // int offset = 4 - remainder;
                // int newpos = pos - offset;
                // int offsetBits = 8 * offset;
                // leftSlice.position(newpos);
                // rightSlice.position(newpos);
                // int l = leftSlice.getInt() >> offsetBits;
                // int r = rightSlice.getInt() >> offsetBits;
                // return l == r;
                // }
                // else {
                // switch (remainder) {
                // case 1:
                // return leftSlice.get() == rightSlice.get();
                // case 2:
                // return leftSlice.getShort() == rightSlice.getShort();
                // case 3:
                // return leftSlice.getShort() == rightSlice.getShort()
                // && leftSlice.get() == rightSlice.get();
                // }
                // }
                for (; i < limit; i++) {
                    if (leftSlice.get() != rightSlice.get()) {
                        return false;
                    }
                }
                return true;
            }
            finally {
                leftSlice.rewind();
                rightSlice.rewind();
            }
        }

        private static int bucketIndex(ByteBuffer buffer, int len) {
            long hashCode = hashCode(buffer, len);

            return (int) (hashCode % BUCKETS);
        }

        private static long hashCode(ByteBuffer buffer, int len) {
            int i = 0;
            long h = 0;
            for (; i + 7 < len; i += 8) {
                long l = buffer.getLong();
                h = 31L * 31 * 31 * 31 * 31 * 31 * 31 * 31 * h
                        + 31L * 31 * 31 * 31 * 31 * 31 * 31 * ((l >> 56 & 0xFF))
                        + 31 * 31 * 31 * 31 * 31 * 31 * ((l >> 48 & 0xFF))
                        + 31 * 31 * 31 * 31 * 31 * ((l >> 40 & 0xFF))
                        + 31 * 31 * 31 * 31 * ((l >> 32 & 0xFF))
                        + 31 * 31 * 31 * ((l >> 24 & 0xFF))
                        + 31 * 31 * ((l >> 16) & 0xFF)
                        + 31 * ((l >> 8) & 0xFF)
                        + (l & 0xFF);
            }
            for (; i < len; i++) {
                h = 31 * h + buffer.get();
            }
            return h & 0xFFFFFFFFL;
        }

        public void forEach(BiConsumer<ByteBuffer, Stats> consumer) {
            for (StatsHolder[] bucket : map) {
                for (StatsHolder statsHolder : bucket) {
                    if (statsHolder != null) {
                        consumer.accept(statsHolder.slice, statsHolder.stats);
                    }
                }
            }
        }
    }

    private static long newLinePattern = compilePattern((byte) '\n');
    private static long semiPattern = compilePattern((byte) ';');

    private static int GRANULARITY = 32 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        int workers = Runtime.getRuntime().availableProcessors() - 1;
        if (args.length == 1) {
            workers = Integer.parseInt(args[0]);
        }
        Map<String, Stats> map = new TreeMap<>();
        File f = new File("measurements.txt");
        ExecutorService workerPool = Executors.newFixedThreadPool(workers);
        ExecutorService mergerPool = Executors.newSingleThreadExecutor();
        try (FileInputStream fis = new FileInputStream(f)) {
            FileChannel fc = fis.getChannel();
            if ((fc.size() / workers) < GRANULARITY) {
                workers = (int) (fc.size() / GRANULARITY) + 1;
            }
            int chunkSize = (int) Math.min(fc.size() / workers, Integer.MAX_VALUE);
            chunkSize = ((chunkSize / GRANULARITY) + 1) * GRANULARITY;
            // System.out.println("Chunk size: " + chunkSize);
            for (ByteBuffer bb : mmap(fc, chunkSize)) {
                workerPool.submit(() -> {
                    try {
                        StatsMap data = processChunk(bb);
                        synchronized (map) {
                            data.forEach((k, v) -> {
                                String str = stringFromBuffer(k);
                                map.merge(str, v, Stats::merge);
                            });
                        }
                    }
                    catch (Throwable t) {
                        t.printStackTrace();
                    }
                });
            }
            workerPool.shutdown();
            workerPool.awaitTermination(1, TimeUnit.HOURS);
            mergerPool.shutdown();
            mergerPool.awaitTermination(1, TimeUnit.HOURS);
        }
        finally {
            // System.out.println("Keys: " + map.size());
            System.out.println(map);
        }
    }

    private static String stringFromBuffer(ByteBuffer bb) {
        bb.rewind();
        int pos = bb.position();
        int limit = bb.limit();
        byte[] bytes = new byte[limit - pos];
        bb.get(bytes);
        return new String(bytes);
    }

    private static StatsMap processChunk(ByteBuffer bb) {
        StatsMap map = new StatsMap();

        LongBuffer lb = bb.asLongBuffer();

        long ptr = 0;
        long limit = lb.limit();
        long backstop = limit - 1;
        int remainder = bb.limit() % 8;
        byte[] tmp = new byte[remainder];
        long currentWord = 0;
        int offset = 8;
        long keyLen = 0;
        long valLen = 0;
        boolean fastParser = true;
        long lineCnt = 0;
        while (ptr < limit) {
            bb.mark();
            int byteIndex = 8;
            if (offset == 8) {
                currentWord = lb.get();
                offset = 0;
                ptr++;
            }

            if ((byteIndex = firstInstance(currentWord, semiPattern)) == 8) {
                long pos = ptr;
                while (ptr++ < limit && ((byteIndex = firstInstance((currentWord = lb.get()), semiPattern)) == 8))
                    ;
                if (byteIndex == 8) {
                    break;
                }
                keyLen = (8 - offset + byteIndex) + (ptr - pos - 1) * 8;
            }
            else {
                keyLen = byteIndex - offset;
            }

            currentWord &= ~(0xFFL << (7 - byteIndex) * 8);
            offset = byteIndex + 1;

            byteIndex = 8;
            fastParser = ptr < backstop;
            if ((byteIndex = firstInstance(currentWord, newLinePattern)) == 8) {
                long pos = ptr;
                if (ptr == backstop) {
                    bb.get((int) ptr * 8, tmp);
                    for (int i = 0; i < remainder; i++) {
                        if (tmp[i] == '\n') {
                            byteIndex = i;
                            break;
                        }
                    }
                    ptr++;
                }
                else {
                    while (ptr++ < limit && (byteIndex = firstInstance(currentWord = lb.get(), newLinePattern)) == 8)
                        ;
                }
                if (byteIndex == 8) {
                    break;
                }
                valLen = (8 - offset + byteIndex) + (ptr - pos - 1) * 8;
            }
            else {
                valLen = byteIndex - offset;
            }
            currentWord &= ~(0xFFL << (7 - byteIndex) * 8);
            offset = byteIndex + 1;

            bb.reset();
            Stats stats = map.getOrInsert(bb, (int) keyLen);
            bb.get();
            short val = fastParse(bb, (int) valLen, fastParser);
            bb.get();

            lineCnt++;
            stats.add(val);
        }
        // System.out.println("Remaining: " + lb.remaining());
        // System.out.println("Lines: " + lineCnt);
        return map;
    }

    private static final long fastParserMask = 0x3030303030303030L;
    private static final long minusPattern = compilePattern((byte) ('-' ^ 0x30));
    private static final long dotPattern = compilePattern((byte) ('.' ^ 0x30));

    private static short fastParse(ByteBuffer bb, int len, boolean fast) {
        assert (len <= 5);
        int targetPos = bb.position() + len;
        long word;
        if (!fast) {
            byte[] bytes = new byte[8];
            bb.get(bytes, 0, len);
            word = ((long) bytes[0] << 56)
                    | ((long) bytes[1] & 0xFF) << 48
                    | ((long) bytes[2] & 0xFF) << 40
                    | ((long) bytes[3] & 0xFF) << 32
                    | ((long) bytes[4] & 0xFF) << 24
                    | ((long) bytes[5] & 0xFF) << 16
                    | ((long) bytes[6] & 0xFF) << 8
                    | ((long) bytes[7] & 0xFF);
        }
        else {
            word = bb.getLong();
        }
        word ^= fastParserMask;
        bb.position(targetPos);

        short val = 0;
        short multiplier = 1;
        byte negative = 0;

        int negPos = firstInstance(word, minusPattern);
        if (negPos == 0) {
            negative = 1;
        }
        assert (negPos == 8);

        int dotPos = firstInstance(word, dotPattern);
        if (dotPos == 8 || (dotPos + negative) >= len) {
            multiplier = 10;
        }

        for (int i = 0; i < len; i++) {
            int digit = (int) ((word >>> (7 - i) * 8) & 0xFF);
            if (digit > 9) {
                continue;
            }
            val = (short) (val * 10 + digit);
        }
        short ret = (short) ((val * multiplier) * (negative == 1 ? -1 : 1));
        return ret;
    }

    private static ByteBuffer[] mmap(FileChannel fc, int splitSize) throws Exception {
        if (fc.size() > splitSize && splitSize < 128) {
            throw new IllegalArgumentException("Split size must be at least 128 bytes");
        }

        byte[] byteBuffer = new byte[128];
        int chunks = (int) (fc.size() / splitSize) + 1;
        ByteBuffer[] buffers = new ByteBuffer[chunks];
        long remaining = fc.size();
        int count = 0;
        for (int j = 0; j < chunks; j++) {
            if (remaining > splitSize) {
                ByteBuffer buffer = fc.map(FileChannel.MapMode.READ_ONLY, fc.size() - remaining, splitSize);
                buffer.get(splitSize - 128, byteBuffer, 0, 128);
                int adjust = -1;
                for (int i = 0; i < 128; i++) {
                    if (byteBuffer[127 - i] == '\n') {
                        adjust = i;
                        break;
                    }
                }
                assert (adjust != -1);
                int size = splitSize - adjust;
                // System.out.println("===> chunk: " + (fc.size() - remaining) + " - " + (fc.size() - remaining + size - 1));
                buffers[j] = fc.map(FileChannel.MapMode.READ_ONLY, fc.size() - remaining, size);
                remaining -= size;
                count = j + 1;
            }
            else {
                count = j + 1;
                // System.out.println("===> chunk: " + (fc.size() - remaining) + " - " + fc.size());
                buffers[j] = fc.map(FileChannel.MapMode.READ_ONLY, fc.size() - remaining, remaining);
                break;
            }
        }
        // System.out.println("Chunks: " + count);
        return count < chunks ? Arrays.copyOf(buffers, count) : buffers;
    }

    private static long compilePattern(byte byteToFind) {
        long pattern = byteToFind & 0xFFL;
        return pattern
                | (pattern << 8)
                | (pattern << 16)
                | (pattern << 24)
                | (pattern << 32)
                | (pattern << 40)
                | (pattern << 48)
                | (pattern << 56);
    }

    private static int firstInstance(long word, long pattern) {
        long input = word ^ pattern;
        long tmp = (input & 0x7F7F7F7F7F7F7F7FL) + 0x7F7F7F7F7F7F7F7FL;
        tmp = ~(tmp | input | 0x7F7F7F7F7F7F7F7FL);
        return Long.numberOfLeadingZeros(tmp) >>> 3;
    }
}