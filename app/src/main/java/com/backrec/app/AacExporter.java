package com.backrec.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Склеивает ADTS-куски в один .m4a и кладёт его в общую папку через MediaStore. */
public final class AacExporter {
    private AacExporter() { }

    public static final class EmptyBufferException extends IOException {
        EmptyBufferException() { super("empty buffer"); }
    }

    public static final class Result {
        public final String name;
        public final long durationMs;
        Result(String name, long durationMs) { this.name = name; this.durationMs = durationMs; }
    }

    /** Число целых ADTS-кадров в файле (оборванный последний кадр не считается). */
    public static long countFrames(File f) {
        long count = 0;
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 65536)) {
            byte[] h = new byte[7];
            while (true) {
                if (!readFully(in, h, 7)) break;
                if ((h[0] & 0xFF) != 0xFF || (h[1] & 0xF0) != 0xF0) break;
                int frameLen = frameLength(h);
                if (frameLen < 7) break;
                if (skipFully(in, frameLen - 7) < frameLen - 7) break;
                count++;
            }
        } catch (IOException ignored) { }
        return count;
    }

    /**
     * @param lastMs сколько последних миллисекунд сохранить; 0 = всё.
     */
    public static Result export(Context ctx, List<File> segments, long lastMs) throws IOException {
        long totalFrames = 0;
        long[] frames = new long[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            frames[i] = countFrames(segments.get(i));
            totalFrames += frames[i];
        }
        if (totalFrames == 0) throw new EmptyBufferException();

        long keep = totalFrames;
        if (lastMs > 0) {
            keep = Math.min(totalFrames,
                    lastMs * RingRecorder.SAMPLE_RATE / 1000L / RingRecorder.SAMPLES_PER_FRAME);
        }
        long skip = totalFrames - keep;

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String name = "BackRec_" + stamp + ".m4a";

        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        cv.put(MediaStore.Audio.Media.RELATIVE_PATH, relativeDir());
        cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("Не удалось создать файл");

        boolean ok = false;
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw")) {
            if (pfd == null) throw new IOException("Не удалось открыть файл");
            mux(pfd, segments, skip, keep);
            ok = true;
        } finally {
            if (ok) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            } else {
                cr.delete(uri, null, null);
            }
        }
        long durMs = keep * RingRecorder.SAMPLES_PER_FRAME * 1000L / RingRecorder.SAMPLE_RATE;
        return new Result(relativeDir() + "/" + name, durMs);
    }

    /** Папка для сохранённых записей относительно корня памяти. */
    public static String relativeDir() {
        String base = Build.VERSION.SDK_INT >= 31 ? Environment.DIRECTORY_RECORDINGS : Environment.DIRECTORY_MUSIC;
        return base + "/BackRecorder";
    }

    private static void mux(ParcelFileDescriptor pfd, List<File> segments, long skip, long keep) throws IOException {
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                RingRecorder.SAMPLE_RATE, RingRecorder.CHANNELS);
        // AudioSpecificConfig: objectType(5) freqIndex(4) channels(4) + 3 нулевых бита
        int objectType = 2;
        byte[] csd = new byte[]{
                (byte) ((objectType << 3) | (RingRecorder.FREQ_INDEX >> 1)),
                (byte) (((RingRecorder.FREQ_INDEX & 1) << 7) | (RingRecorder.CHANNELS << 3))
        };
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, RingRecorder.BIT_RATE);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);

        MediaMuxer muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = muxer.addTrack(fmt);
        muxer.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ByteBuffer buf = ByteBuffer.allocateDirect(16384);
        byte[] h = new byte[9];
        byte[] payload = new byte[16384];
        long index = 0;      // номер кадра во всём буфере
        long written = 0;
        try {
            for (File f : segments) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(f), 65536)) {
                    while (written < keep) {
                        if (!readFully(in, h, 7)) break;
                        if ((h[0] & 0xFF) != 0xFF || (h[1] & 0xF0) != 0xF0) break;
                        int frameLen = frameLength(h);
                        boolean hasCrc = (h[1] & 0x01) == 0;
                        int hdr = hasCrc ? 9 : 7;
                        if (hasCrc && !readFully(in, h, 2)) break;
                        int len = frameLen - hdr;
                        if (len <= 0 || len > payload.length) break;
                        if (!readFully(in, payload, len)) break;
                        if (index++ < skip) continue;

                        buf.clear();
                        buf.put(payload, 0, len);
                        buf.flip();
                        info.offset = 0;
                        info.size = len;
                        info.presentationTimeUs = written * RingRecorder.SAMPLES_PER_FRAME * 1_000_000L
                                / RingRecorder.SAMPLE_RATE;
                        info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                        muxer.writeSampleData(track, buf, info);
                        written++;
                    }
                }
            }
        } finally {
            try { muxer.stop(); } catch (Exception ignored) { }
            muxer.release();
        }
        if (written == 0) throw new IOException("Нечего сохранять");
    }

    private static int frameLength(byte[] h) {
        return ((h[3] & 0x03) << 11) | ((h[4] & 0xFF) << 3) | ((h[5] & 0xE0) >> 5);
    }

    private static boolean readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(b, off, len - off);
            if (r < 0) return false;
            off += r;
        }
        return true;
    }

    private static long skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) break;
                s = 1;
            }
            left -= s;
        }
        return n - left;
    }
}
