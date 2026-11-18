package com.backrec.app;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Пишет микрофон непрерывно в AAC (ADTS) кусками по SEGMENT_MS в папку dir.
 * Старые куски удаляются, когда суммарная длина превышает bufferMs.
 * ADTS-кадры самодостаточны, поэтому куски склеиваются без швов.
 */
public class RingRecorder {
    private static final String TAG = "RingRecorder";

    public static final int SAMPLE_RATE = 44100;
    public static final int FREQ_INDEX = 4;          // индекс 44100 в таблице ADTS
    public static final int CHANNELS = 1;
    public static final int BIT_RATE = 64000;        // ~29 МБ в час
    public static final int SAMPLES_PER_FRAME = 1024;
    static final long SEGMENT_MS = 30_000;

    private final File dir;
    private volatile long bufferMs;
    private volatile boolean running;
    private Thread thread;

    private final Object lock = new Object();
    private FileOutputStream out;
    private File currentFile;
    private long currentFrames;
    private int exporting = 0;
    /** Кэш числа кадров в закрытых кусках. */
    private final Map<String, Long> frameCache = new HashMap<>();

    public interface ErrorListener { void onError(Exception e); }
    private final ErrorListener errorListener;

    public RingRecorder(File dir, long bufferMs, ErrorListener l) {
        this.dir = dir;
        this.bufferMs = bufferMs;
        this.errorListener = l;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public void setBufferMs(long ms) {
        bufferMs = ms;
        synchronized (lock) { prune(); }
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "ring-recorder");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(3000); } catch (InterruptedException ignored) { }
            thread = null;
        }
    }

    public boolean isRunning() { return running; }

    /** Сколько миллисекунд сейчас лежит в буфере (с учётом текущего куска). */
    public long bufferedMs() {
        synchronized (lock) {
            long frames = currentFrames;
            for (File f : closedSegments()) frames += framesOf(f);
            return Math.min(frames * SAMPLES_PER_FRAME * 1000L / SAMPLE_RATE, bufferMs + SEGMENT_MS);
        }
    }

    /**
     * Закрывает текущий кусок (чтобы в сохранение попали самые свежие секунды),
     * блокирует удаление и возвращает список готовых кусков. После экспорта
     * обязательно вызвать {@link #releaseSegments()}.
     */
    public List<File> lockSegmentsForExport() {
        synchronized (lock) {
            exporting++;
            if (running && out != null) {
                try { openNewSegment(); } catch (IOException e) { Log.e(TAG, "rotate", e); }
            }
            return closedSegments();
        }
    }

    /** Стирает всё накопленное; запись (если идёт) продолжается в новый кусок. */
    public int clearBuffer() {
        synchronized (lock) {
            int n = 0;
            if (running && out != null) {
                try { openNewSegment(); } catch (IOException e) { Log.e(TAG, "rotate", e); }
            }
            for (File f : closedSegments()) {
                if (f.delete()) n++;
                frameCache.remove(f.getName());
            }
            return n;
        }
    }

    public void releaseSegments() {
        synchronized (lock) {
            exporting--;
            prune();
        }
    }

    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private void loop() {
        AudioRecord rec = null;
        MediaCodec codec = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf * 4, SAMPLE_RATE * 2));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("microphone unavailable (busy?)");
            }

            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            synchronized (lock) { openNewSegment(); }
            rec.startRecording();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long totalSamples = 0;
            byte[] tmp = new byte[8192];

            while (running) {
                int inIdx = codec.dequeueInputBuffer(10_000);
                if (inIdx >= 0) {
                    ByteBuffer in = codec.getInputBuffer(inIdx);
                    in.clear();
                    int want = Math.min(in.remaining(), 4096);
                    int n = rec.read(in, want);
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, 0);
                        throw new IllegalStateException("AudioRecord.read = " + n);
                    }
                    long ptsUs = totalSamples * 1_000_000L / SAMPLE_RATE;
                    codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0);
                    totalSamples += n / 2;
                }
                drain(codec, info, tmp);
            }
            Log.i(TAG, "record loop finished normally, samples=" + totalSamples);
        } catch (Exception e) {
            Log.e(TAG, "record loop", e);
            running = false;
            if (errorListener != null) errorListener.onError(e);
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
            }
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            synchronized (lock) { closeCurrent(); prune(); }
        }
    }

    private void drain(MediaCodec codec, MediaCodec.BufferInfo info, byte[] tmp) throws IOException {
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, 0);
            if (idx < 0) return;
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(idx);
                outBuf.position(info.offset);
                outBuf.limit(info.offset + info.size);
                int len = info.size;
                byte[] data = len + 7 <= tmp.length ? tmp : new byte[len + 7];
                writeAdtsHeader(data, len + 7);
                outBuf.get(data, 7, len);
                synchronized (lock) {
                    if (out != null) {
                        out.write(data, 0, len + 7);
                        currentFrames++;
                        if (currentFrames * SAMPLES_PER_FRAME * 1000L / SAMPLE_RATE >= SEGMENT_MS) {
                            openNewSegment();
                        }
                    }
                }
            }
            codec.releaseOutputBuffer(idx, false);
        }
    }

    private static void writeAdtsHeader(byte[] p, int fullLen) {
        int profile = 2; // AAC LC
        p[0] = (byte) 0xFF;
        p[1] = (byte) 0xF1;
        p[2] = (byte) (((profile - 1) << 6) + (FREQ_INDEX << 2) + (CHANNELS >> 2));
        p[3] = (byte) (((CHANNELS & 3) << 6) + (fullLen >> 11));
        p[4] = (byte) ((fullLen & 0x7FF) >> 3);
        p[5] = (byte) (((fullLen & 7) << 5) + 0x1F);
        p[6] = (byte) 0xFC;
    }

    /** Вызывать под lock. */
    private void openNewSegment() throws IOException {
        closeCurrent();
        // Имя = время начала куска; nanoTime-хвост на случай двух ротаций в одну мс.
        String name = String.format(Locale.US, "seg_%013d_%04d.aac",
                System.currentTimeMillis(), (System.nanoTime() / 1000) % 10000);
        currentFile = new File(dir, name);
        out = new FileOutputStream(currentFile);
        currentFrames = 0;
        prune();
    }

    /** Вызывать под lock. */
    private void closeCurrent() {
        if (out != null) {
            try { out.flush(); out.getFD().sync(); } catch (IOException ignored) { }
            try { out.close(); } catch (IOException ignored) { }
            if (currentFile != null) {
                if (currentFrames == 0) {
                    //noinspection ResultOfMethodCallIgnored
                    currentFile.delete();
                } else {
                    frameCache.put(currentFile.getName(), currentFrames);
                }
            }
        }
        out = null;
        currentFile = null;
        currentFrames = 0;
    }

    /** Готовые куски, от старых к новым. Вызывать под lock. */
    private List<File> closedSegments() {
        File[] files = dir.listFiles((d, n) -> n.startsWith("seg_") && n.endsWith(".aac"));
        List<File> list = new ArrayList<>();
        if (files == null) return list;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            if (currentFile != null && f.getName().equals(currentFile.getName())) continue;
            list.add(f);
        }
        return list;
    }

    private long framesOf(File f) {
        Long c = frameCache.get(f.getName());
        if (c == null) {
            c = AacExporter.countFrames(f);
            frameCache.put(f.getName(), c);
        }
        return c;
    }

    /** Удаляет самые старые куски, пока остальных хватает на bufferMs. Под lock. */
    private void prune() {
        if (exporting > 0) return;
        List<File> segs = closedSegments();
        long needFrames = bufferMs * SAMPLE_RATE / 1000L / SAMPLES_PER_FRAME;
        long total = currentFrames;
        for (File f : segs) total += framesOf(f);
        for (File f : segs) {
            long fr = framesOf(f);
            if (total - fr < needFrames) break;
            if (f.delete()) {
                total -= fr;
                frameCache.remove(f.getName());
            }
        }
    }
}
