package com.anki.cozmo;

import android.content.Context;
import android.media.AudioFormat;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Android 15 replacement for the legacy Acapela bridge used by Cozmo 3.6.6.
 *
 * The native Cozmo engine already exposes callback(short[], long) and expects
 * signed mono PCM samples. The legacy implementation fed Acapela's 22 kHz
 * sample callbacks into this JNI function. This replacement keeps that ABI
 * unchanged and swaps only the synthesizer backend.
 */
public class CozmoTextToSpeech {
    private static final String TAG = "CozmoTTS.Android";
    private static final int RESULT_OK = 0;
    private static final int RESULT_FAIL = 1;
    private static final int RESULT_FAIL_IO = 33554432;
    private static final int RESULT_FAIL_INVALID_PARAMETER = 50331648;

    private static final int TARGET_SAMPLE_RATE = 22050;
    private static final int SILENCE_MS = 50;
    private static final int CALLBACK_CHUNK_SAMPLES = 4096;
    private static final long INIT_TIMEOUT_SECONDS = 8;
    private static final long SYNTH_TIMEOUT_SECONDS = 30;

    private static CozmoTextToSpeech sInstance;

    private final Context context;
    private final Object synthLock = new Object();
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private volatile int initStatus = TextToSpeech.ERROR;
    private TextToSpeech tts;
    private int count;

    private static native void callback(short[] samples, long count);

    public CozmoTextToSpeech(Context context) {
        this.context = context.getApplicationContext();
        try {
            this.tts = new TextToSpeech(this.context, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    initStatus = status;
                    initLatch.countDown();
                    Log.i(TAG, "Android TTS init status=" + status);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Unable to create Android TextToSpeech", t);
            initStatus = TextToSpeech.ERROR;
            initLatch.countDown();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            shutdown();
        } finally {
            super.finalize();
        }
    }

    private void shutdown() {
        TextToSpeech local = this.tts;
        this.tts = null;
        if (local != null) {
            try {
                local.stop();
                local.shutdown();
            } catch (Throwable t) {
                Log.w(TAG, "TTS shutdown failed", t);
            }
        }
    }

    private boolean awaitInit() {
        try {
            if (!initLatch.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.e(TAG, "Timed out waiting for Android TTS init");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return initStatus == TextToSpeech.SUCCESS && tts != null;
    }

    private static Locale localeForVoice(String voice) {
        if (voice == null) {
            return Locale.US;
        }
        String v = voice.toLowerCase(Locale.US);
        if (v.contains("bruno") || v.contains("frf") || v.contains("french")) {
            return Locale.FRANCE;
        }
        if (v.contains("klaus") || v.contains("ged") || v.contains("german")) {
            return Locale.GERMANY;
        }
        if (v.contains("sakura") || v.contains("ja_jp") || v.contains("japanese")) {
            return Locale.JAPAN;
        }
        return Locale.US;
    }

    private void preferOfflineVoice(Locale locale) {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) {
                return;
            }
            Voice best = null;
            for (Voice v : voices) {
                Locale l = v.getLocale();
                if (l == null || !l.getLanguage().equals(locale.getLanguage())) {
                    continue;
                }
                if (v.isNetworkConnectionRequired()) {
                    continue;
                }
                if (best == null || v.getQuality() > best.getQuality()) {
                    best = v;
                }
            }
            if (best != null) {
                int rc = tts.setVoice(best);
                Log.i(TAG, "Selected offline voice=" + best.getName() + " rc=" + rc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to select offline TTS voice", t);
        }
    }

    public int doLoadVoice(String path, String voice, int userId, int password, String license) {
        if (!awaitInit()) {
            return RESULT_FAIL_INVALID_PARAMETER;
        }

        Locale locale = localeForVoice(voice);
        try {
            int available = tts.isLanguageAvailable(locale);
            if (available < TextToSpeech.LANG_AVAILABLE) {
                Log.e(TAG, "Language unavailable: " + locale + " result=" + available);
                return RESULT_FAIL_INVALID_PARAMETER;
            }

            int rc = tts.setLanguage(locale);
            if (rc < TextToSpeech.LANG_AVAILABLE) {
                Log.e(TAG, "setLanguage failed: " + locale + " result=" + rc);
                return RESULT_FAIL_INVALID_PARAMETER;
            }

            preferOfflineVoice(locale);
            Log.i(TAG, "Legacy Acapela voice '" + voice + "' mapped to Android locale " + locale);
            return RESULT_OK;
        } catch (Throwable t) {
            Log.e(TAG, "doLoadVoice failed", t);
            return RESULT_FAIL_INVALID_PARAMETER;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String sanitizeAcapelaMarkup(String text) {
        if (text == null) {
            return "";
        }
        // Engine tts_config.json replaces the word Cozmo with Acapela \Prn=...\
        // markup for some locales. Android TTS must receive normal text.
        String out = text.replaceAll("\\\\Prn=[^\\\\]*\\\\", "Cozmo");
        // Best-effort handling for any other simple Acapela control sequence.
        out = out.replaceAll("\\\\[A-Za-z]+=[^\\\\]*\\\\", " ");
        return out;
    }

    public int doCreateAudioData(String text, int speed, int shaping, int pitch) {
        if (!awaitInit()) {
            return RESULT_FAIL_INVALID_PARAMETER;
        }

        synchronized (synthLock) {
            final AudioCollector collector = new AudioCollector();
            final String utteranceId = "cozmo-" + UUID.randomUUID().toString();
            File tmp = null;

            try {
                count = 0;

                // Acapela Android config uses 50 as roughly the base English rate.
                float speechRate = clamp(speed > 0 ? speed / 50.0f : 1.0f, 0.45f, 2.0f);
                float speechPitch = clamp(pitch > 0 ? pitch / 100.0f : 1.0f, 0.5f, 2.0f);

                if (tts.setSpeechRate(speechRate) != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "setSpeechRate failed for " + speechRate);
                }
                if (tts.setPitch(speechPitch) != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "setPitch failed for " + speechPitch);
                }

                // VOICESHAPE is Acapela-specific. Cozmo's downstream
                // Cozmo_Voice_Processing audio switch remains unchanged.
                Log.i(TAG, "synth text rate=" + speechRate + " pitch=" + speechPitch
                        + " shaping(ignored)=" + shaping);

                tts.setOnUtteranceProgressListener(collector);

                String cleanText = sanitizeAcapelaMarkup(text);
                tmp = File.createTempFile("cozmo_tts_", ".wav", context.getCacheDir());

                int start = tts.synthesizeToFile(cleanText, new Bundle(), tmp, utteranceId);
                if (start != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "synthesizeToFile start failed rc=" + start);
                    return RESULT_FAIL;
                }

                if (!collector.done.await(SYNTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.e(TAG, "TTS synthesis timed out");
                    tts.stop();
                    return RESULT_FAIL_IO;
                }
                if (collector.failed) {
                    Log.e(TAG, "TTS synthesis reported an error");
                    return RESULT_FAIL;
                }

                PcmData pcm = collector.toPcmData();
                if (pcm == null || pcm.samples == null || pcm.samples.length == 0) {
                    pcm = readWaveFallback(tmp);
                }
                if (pcm == null || pcm.samples == null || pcm.samples.length == 0) {
                    Log.e(TAG, "TTS returned no PCM samples");
                    return RESULT_FAIL_IO;
                }

                short[] mono = toMono(pcm.samples, pcm.channels);
                short[] resampled = resample(mono, pcm.sampleRate, TARGET_SAMPLE_RATE);
                short[] withSilence = addSilence(resampled, TARGET_SAMPLE_RATE, SILENCE_MS);

                sendToNative(withSilence);
                Log.i(TAG, "generated samples=" + count + " sourceRate=" + pcm.sampleRate
                        + " sourceChannels=" + pcm.channels + " targetRate=" + TARGET_SAMPLE_RATE);
                return RESULT_OK;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RESULT_FAIL_IO;
            } catch (Throwable t) {
                Log.e(TAG, "doCreateAudioData failed", t);
                return RESULT_FAIL_IO;
            } finally {
                if (tmp != null) {
                    try {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void sendToNative(short[] samples) {
        int offset = 0;
        while (offset < samples.length) {
            int n = Math.min(CALLBACK_CHUNK_SAMPLES, samples.length - offset);
            short[] chunk = new short[n];
            System.arraycopy(samples, offset, chunk, 0, n);
            callback(chunk, n);
            count += n;
            offset += n;
        }
    }

    private static short[] addSilence(short[] input, int sampleRate, int silenceMs) {
        int silence = Math.max(0, (sampleRate * silenceMs) / 1000);
        short[] out = new short[input.length + (silence * 2)];
        System.arraycopy(input, 0, out, silence, input.length);
        return out;
    }

    private static short[] toMono(short[] input, int channels) {
        if (channels <= 1) {
            return input;
        }
        int frames = input.length / channels;
        short[] out = new short[frames];
        for (int f = 0; f < frames; f++) {
            long sum = 0;
            int base = f * channels;
            for (int c = 0; c < channels; c++) {
                sum += input[base + c];
            }
            out[f] = (short) (sum / channels);
        }
        return out;
    }

    private static short[] resample(short[] input, int sourceRate, int targetRate) {
        if (sourceRate <= 0 || sourceRate == targetRate || input.length < 2) {
            return input;
        }

        int outLength = Math.max(1, (int) Math.round(input.length * (double) targetRate / sourceRate));
        short[] out = new short[outLength];
        double step = (double) sourceRate / targetRate;

        for (int i = 0; i < outLength; i++) {
            double sourcePos = i * step;
            int index = (int) sourcePos;
            if (index >= input.length - 1) {
                out[i] = input[input.length - 1];
            } else {
                double frac = sourcePos - index;
                double sample = input[index] + ((input[index + 1] - input[index]) * frac);
                out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample)));
            }
        }
        return out;
    }

    private static short[] decodePcm(byte[] bytes, int encoding) {
        if (bytes == null || bytes.length == 0) {
            return new short[0];
        }

        if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
            short[] out = new short[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                out[i] = (short) (((bytes[i] & 0xff) - 128) << 8);
            }
            return out;
        }

        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            int count = bytes.length / 4;
            short[] out = new short[count];
            for (int i = 0; i < count; i++) {
                int p = i * 4;
                int bits = (bytes[p] & 0xff)
                        | ((bytes[p + 1] & 0xff) << 8)
                        | ((bytes[p + 2] & 0xff) << 16)
                        | ((bytes[p + 3] & 0xff) << 24);
                float f = Float.intBitsToFloat(bits);
                f = clamp(f, -1.0f, 1.0f);
                out[i] = (short) Math.round(f * 32767.0f);
            }
            return out;
        }

        // Android TTS normally reports ENCODING_PCM_16BIT.
        int count = bytes.length / 2;
        short[] out = new short[count];
        for (int i = 0; i < count; i++) {
            int p = i * 2;
            out[i] = (short) ((bytes[p] & 0xff) | (bytes[p + 1] << 8));
        }
        return out;
    }

    private static PcmData readWaveFallback(File file) {
        if (file == null || !file.isFile() || file.length() < 44) {
            return null;
        }
        try {
            byte[] all = new byte[(int) Math.min(file.length(), Integer.MAX_VALUE)];
            FileInputStream in = new FileInputStream(file);
            int pos = 0;
            while (pos < all.length) {
                int n = in.read(all, pos, all.length - pos);
                if (n < 0) {
                    break;
                }
                pos += n;
            }
            in.close();

            if (pos < 44 || all[0] != 'R' || all[1] != 'I' || all[2] != 'F' || all[3] != 'F') {
                return null;
            }

            int channels = 1;
            int sampleRate = TARGET_SAMPLE_RATE;
            int bits = 16;
            int format = 1;
            int dataOffset = -1;
            int dataLength = 0;

            int p = 12;
            while (p + 8 <= pos) {
                int size = le32(all, p + 4);
                if (p + 8 + size > pos) {
                    break;
                }

                if (all[p] == 'f' && all[p + 1] == 'm' && all[p + 2] == 't' && all[p + 3] == ' ') {
                    if (size >= 16) {
                        format = le16(all, p + 8);
                        channels = le16(all, p + 10);
                        sampleRate = le32(all, p + 12);
                        bits = le16(all, p + 22);
                    }
                } else if (all[p] == 'd' && all[p + 1] == 'a' && all[p + 2] == 't' && all[p + 3] == 'a') {
                    dataOffset = p + 8;
                    dataLength = size;
                    break;
                }
                p += 8 + size + (size & 1);
            }

            if (dataOffset < 0 || dataLength <= 0 || dataOffset + dataLength > pos) {
                return null;
            }

            byte[] pcm = new byte[dataLength];
            System.arraycopy(all, dataOffset, pcm, 0, dataLength);

            int encoding;
            if (format == 3 && bits == 32) {
                encoding = AudioFormat.ENCODING_PCM_FLOAT;
            } else if (bits == 8) {
                encoding = AudioFormat.ENCODING_PCM_8BIT;
            } else {
                encoding = AudioFormat.ENCODING_PCM_16BIT;
            }

            return new PcmData(decodePcm(pcm, encoding), sampleRate, Math.max(1, channels));
        } catch (Throwable t) {
            Log.w(TAG, "Unable to parse synthesized WAV fallback", t);
            return null;
        }
    }

    private static int le16(byte[] b, int p) {
        return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xff)
                | ((b[p + 1] & 0xff) << 8)
                | ((b[p + 2] & 0xff) << 16)
                | ((b[p + 3] & 0xff) << 24);
    }

    private static final class PcmData {
        final short[] samples;
        final int sampleRate;
        final int channels;

        PcmData(short[] samples, int sampleRate, int channels) {
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    private static final class AudioCollector extends UtteranceProgressListener {
        final CountDownLatch done = new CountDownLatch(1);
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        volatile int sampleRate = TARGET_SAMPLE_RATE;
        volatile int encoding = AudioFormat.ENCODING_PCM_16BIT;
        volatile int channels = 1;
        volatile boolean failed;

        @Override
        public void onStart(String utteranceId) {
        }

        @Override
        public void onBeginSynthesis(String utteranceId, int sampleRateInHz, int audioFormat, int channelCount) {
            this.sampleRate = sampleRateInHz;
            this.encoding = audioFormat;
            this.channels = Math.max(1, channelCount);
            Log.i(TAG, "begin synthesis rate=" + sampleRateInHz + " format=" + audioFormat
                    + " channels=" + channelCount);
        }

        @Override
        public synchronized void onAudioAvailable(String utteranceId, byte[] buffer) {
            if (buffer != null && buffer.length > 0) {
                audio.write(buffer, 0, buffer.length);
            }
        }

        @Override
        public void onDone(String utteranceId) {
            done.countDown();
        }

        @Override
        public void onError(String utteranceId) {
            failed = true;
            done.countDown();
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            Log.e(TAG, "synthesis error=" + errorCode);
            failed = true;
            done.countDown();
        }

        synchronized PcmData toPcmData() {
            byte[] raw = audio.toByteArray();
            if (raw.length == 0) {
                return null;
            }
            return new PcmData(decodePcm(raw, encoding), sampleRate, channels);
        }
    }

    public static void register(Context context) {
        if (sInstance != null) {
            sInstance.shutdown();
        }
        sInstance = new CozmoTextToSpeech(context);
    }

    public static void unregister() {
        CozmoTextToSpeech old = sInstance;
        sInstance = null;
        if (old != null) {
            old.shutdown();
        }
    }

    public static int loadVoice(String path, String voice, int userId, int password, String license) {
        CozmoTextToSpeech instance = sInstance;
        if (instance == null) {
            return RESULT_FAIL_INVALID_PARAMETER;
        }
        return instance.doLoadVoice(path, voice, userId, password, license);
    }

    public static int createAudioData(String text, int speed, int shaping, int pitch) {
        CozmoTextToSpeech instance = sInstance;
        if (instance == null) {
            return RESULT_FAIL_INVALID_PARAMETER;
        }
        return instance.doCreateAudioData(text, speed, shaping, pitch);
    }
}
