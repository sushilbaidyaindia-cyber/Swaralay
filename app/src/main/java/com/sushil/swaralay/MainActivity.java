package com.sushil.swaralay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.MediaMetadata;
import android.provider.OpenableColumns;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.content.ContentValues;
import java.io.OutputStream;
import android.provider.MediaStore;
import android.util.Base64;
import android.media.MediaRecorder;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaCodec;
import java.nio.ByteBuffer;
import java.io.File;
import java.io.FileInputStream;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.view.accessibility.AccessibilityManager;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private ValueCallback<Uri[]> filePathCallback;
    private MediaRecorder nativeRecorder;
    private File nativeRecordFile;
    private boolean isNativeRecording = false;
    private volatile boolean monitorRunning = false;
    private Thread monitorThread;
    private AudioRecord monitorRecord;
    private AudioTrack monitorTrack;
    private volatile float monitorGain = 1.6f;
    private volatile float monitorEcho = 0f;
    private volatile float monitorReverb = 0f;
    private AudioManager audioManager;

    // ---- Monitor DSP / capture state ----
    private volatile int monitorRevPreMs = 0, monitorRevPanPct = 0;
    private volatile float monitorRevPanHz = 0.06f;
    private volatile int monitorRevType = 1; // 0 room, 1 hall, 2 plate, 3 cathedral
    private volatile boolean monitorCapturing = false;
    private boolean recordingViaMonitor = false;
    private final Object captureLock = new Object();
    private java.io.BufferedOutputStream captureOut = null;
    private File captureFile = null;
    private int captureSampleRate = 44100;
    private int monitorSampleRate = 44100;
    private byte[] stagedBytes = null;

    // ---- Monitor options / state ----
    private volatile boolean optRecordFx = false;  // record WITH the monitor effects (else raw mic only)
    private boolean captureFx = false;             // mode of the recording that is running now
    private int captureChannels = 1;
    private volatile boolean optLowCut = true;
    private volatile boolean optGate = false;
    private volatile String optMicMode = "clear";  // "clear" = raw MIC, "voice" = phone voice filtering
    private volatile boolean optSafeLatency = false;
    private String activeMicMode = "clear";
    private boolean activeSafeLatency = false;
    private volatile boolean monitorIrOk = false;
    private String monitorSourceName = "";
    private android.media.audiofx.NoiseSuppressor monitorNs = null;
    private final java.util.HashMap<String, MonitorDsp.Wav> irWavCache = new java.util.HashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger convToken = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicReference<ConvMsg> pendingConv = new java.util.concurrent.atomic.AtomicReference<>(null);
    private static final String[] IR_NAMES = {"room", "hall", "plate", "cathedral"};

    private static final class ConvMsg {
        final MonitorDsp.StereoConvolver conv;
        ConvMsg(MonitorDsp.StereoConvolver c) { conv = c; }
    }

    /** Reads ir-<name>.wav from the assets folder (same files the FX panel uses). */
    private MonitorDsp.Wav loadIrWav(String name) {
        synchronized (irWavCache) {
            MonitorDsp.Wav w = irWavCache.get(name);
            if (w != null) return w;
            InputStream in = null;
            try {
                in = getAssets().open("ir-" + name + ".wav");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] tmp = new byte[16384];
                int n;
                while ((n = in.read(tmp)) != -1) bo.write(tmp, 0, n);
                w = MonitorDsp.parseWav(bo.toByteArray());
                irWavCache.put(name, w);
                return w;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            } finally {
                if (in != null) { try { in.close(); } catch (Exception ignored) {} }
            }
        }
    }

    private MonitorDsp.StereoConvolver buildConvolver(int typeId, int sr) {
        try {
            String name = IR_NAMES[Math.max(0, Math.min(IR_NAMES.length - 1, typeId))];
            MonitorDsp.Wav w = loadIrWav(name);
            if (w == null) return null;
            float[][] ir = MonitorDsp.prepareIr(w, sr, 4.6);
            return MonitorDsp.StereoConvolver.fromIr(ir, 1024);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    private void releaseMonitorEffects() {
        try {
            if (monitorNs != null) {
                try { monitorNs.release(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        monitorNs = null;
    }

    // ===================== MONITOR DSP (pure Java, no Android classes) =====================
    // BEGIN MonitorDsp
    static final class MonitorDsp {

        // ---------- FFT (radix-2, in place) ----------
        static final class Fft {
            final int n;
            final float[] cos, sin;
            final int[] rev;

            Fft(int n) {
                this.n = n;
                cos = new float[n / 2];
                sin = new float[n / 2];
                for (int i = 0; i < n / 2; i++) {
                    double a = -2.0 * Math.PI * i / n;
                    cos[i] = (float) Math.cos(a);
                    sin[i] = (float) Math.sin(a);
                }
                rev = new int[n];
                int bits = Integer.numberOfTrailingZeros(n);
                for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
            }

            void transform(float[] re, float[] im, boolean inverse) {
                for (int i = 0; i < n; i++) {
                    int j = rev[i];
                    if (j > i) {
                        float t = re[i]; re[i] = re[j]; re[j] = t;
                        t = im[i]; im[i] = im[j]; im[j] = t;
                    }
                }
                for (int size = 2; size <= n; size <<= 1) {
                    int half = size >> 1;
                    int step = n / size;
                    for (int i = 0; i < n; i += size) {
                        for (int j = i, k = 0; j < i + half; j++, k += step) {
                            float wr = cos[k];
                            float wi = inverse ? -sin[k] : sin[k];
                            int l = j + half;
                            float tr = re[l] * wr - im[l] * wi;
                            float ti = re[l] * wi + im[l] * wr;
                            re[l] = re[j] - tr;
                            im[l] = im[j] - ti;
                            re[j] += tr;
                            im[j] += ti;
                        }
                    }
                }
                if (inverse) {
                    float s = 1f / n;
                    for (int i = 0; i < n; i++) { re[i] *= s; im[i] *= s; }
                }
            }
        }

        // ---------- WAV reading + impulse-response preparation ----------
        static final class Wav {
            int sampleRate;
            float[][] ch;
        }

        private static int u16(byte[] b, int i) {
            return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8);
        }

        private static long u32(byte[] b, int i) {
            return (b[i] & 0xffL) | ((b[i + 1] & 0xffL) << 8) | ((b[i + 2] & 0xffL) << 16) | ((b[i + 3] & 0xffL) << 24);
        }

        /** Reads PCM 8/16/24/32-bit and 32-bit float WAV files (mono or multi-channel). */
        static Wav parseWav(byte[] b) throws Exception {
            if (b == null || b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') {
                throw new Exception("not_a_wav");
            }
            int pos = 12;
            int tag = 0, channels = 0, rate = 0, bits = 0;
            int dataPos = -1, dataLen = 0;
            while (pos + 8 <= b.length) {
                String id = new String(b, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
                long sz = u32(b, pos + 4);
                int body = pos + 8;
                if (id.equals("fmt ")) {
                    tag = u16(b, body);
                    channels = u16(b, body + 2);
                    rate = (int) u32(b, body + 4);
                    bits = u16(b, body + 14);
                    if (tag == 0xFFFE && sz >= 26) tag = u16(b, body + 24);
                } else if (id.equals("data")) {
                    dataPos = body;
                    dataLen = (int) Math.min(sz, (long) (b.length - body));
                    break;
                }
                long next = (long) body + sz + (sz & 1L);
                if (next > b.length) break;
                pos = (int) next;
            }
            if (dataPos < 0 || channels < 1 || rate < 8000 || bits < 8) throw new Exception("bad_wav");
            boolean isFloat = (tag == 3);
            if (!(tag == 1 || tag == 3)) throw new Exception("unsupported_format_" + tag);
            if (isFloat && bits != 32) throw new Exception("unsupported_float_bits");
            int bytes = bits / 8;
            int frames = dataLen / (bytes * channels);
            if (frames < 16) throw new Exception("too_short");
            Wav w = new Wav();
            w.sampleRate = rate;
            w.ch = new float[channels][frames];
            int p = dataPos;
            for (int f = 0; f < frames; f++) {
                for (int c = 0; c < channels; c++) {
                    float v;
                    if (isFloat) {
                        int bitsInt = (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8) | ((b[p + 2] & 0xff) << 16) | ((b[p + 3] & 0xff) << 24);
                        v = Float.intBitsToFloat(bitsInt);
                    } else if (bits == 16) {
                        v = (short) ((b[p] & 0xff) | (b[p + 1] << 8)) / 32768f;
                    } else if (bits == 24) {
                        int iv = (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8) | (b[p + 2] << 16);
                        v = iv / 8388608f;
                    } else if (bits == 32) {
                        int iv = (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8) | ((b[p + 2] & 0xff) << 16) | (b[p + 3] << 24);
                        v = iv / 2147483648f;
                    } else if (bits == 8) {
                        v = ((b[p] & 0xff) - 128) / 128f;
                    } else {
                        throw new Exception("unsupported_bits_" + bits);
                    }
                    w.ch[c][f] = v;
                    p += bytes;
                }
            }
            return w;
        }

        /**
         * Turns a decoded IR into a stereo IR at the monitor's sample rate:
         * resample, cap length, remove DC, fade the tail, normalise so the wet level
         * is about the same as the dry level for every impulse response.
         */
        static float[][] prepareIr(Wav w, int targetRate, double maxSeconds) throws Exception {
            float[] a = w.ch[0];
            float[] bch = (w.ch.length > 1) ? w.ch[1] : w.ch[0];
            double ratio = (double) targetRate / (double) w.sampleRate;
            int outLen = (int) Math.floor(a.length * ratio);
            int maxLen = (int) (maxSeconds * targetRate);
            if (outLen > maxLen) outLen = maxLen;
            if (outLen < 64) throw new Exception("ir_too_short");
            float[][] out = new float[2][outLen];
            for (int c = 0; c < 2; c++) {
                float[] src = (c == 0) ? a : bch;
                double mean = 0;
                for (int i = 0; i < outLen; i++) {
                    double pos = i / ratio;
                    int j = (int) pos;
                    float f = (float) (pos - j);
                    float s0 = src[Math.min(j, src.length - 1)];
                    float s1 = src[Math.min(j + 1, src.length - 1)];
                    float v = s0 + (s1 - s0) * f;
                    out[c][i] = v;
                    mean += v;
                }
                float m = (float) (mean / outLen);
                for (int i = 0; i < outLen; i++) out[c][i] -= m;
                int fade = Math.max(8, (int) (outLen * 0.08));
                for (int i = 0; i < fade; i++) {
                    int idx = outLen - fade + i;
                    float g = 0.5f * (1f + (float) Math.cos(Math.PI * i / (double) fade));
                    out[c][idx] *= g;
                }
            }
            double e = 0;
            for (int i = 0; i < outLen; i++) e += (double) out[0][i] * out[0][i] + (double) out[1][i] * out[1][i];
            e *= 0.5;
            if (e < 1e-12) throw new Exception("ir_silent");
            float g = (float) (1.0 / Math.sqrt(e));
            for (int c = 0; c < 2; c++) for (int i = 0; i < outLen; i++) out[c][i] *= g;
            return out;
        }

        // ---------- Uniform partitioned convolution: mono in, stereo out ----------
        static final class StereoConvolver {
            final int B, N, bins, P;
            final Fft fft;
            final float[][] hLr, hLi, hRr, hRi;
            final float[][] xr, xi;
            int fdlPos = 0;
            final float[] inBuf;
            final float[] tr, ti;
            final float[] aLr, aLi, aRr, aRi;
            final float[] outL, outR;
            int pos = 0;
            float wetL, wetR;

            private StereoConvolver(float[][] ir, int blockSize) {
                B = blockSize;
                N = 2 * B;
                bins = B + 1;
                int len = ir[0].length;
                P = Math.max(1, (len + B - 1) / B);
                fft = new Fft(N);
                hLr = new float[P][bins]; hLi = new float[P][bins];
                hRr = new float[P][bins]; hRi = new float[P][bins];
                xr = new float[P][bins]; xi = new float[P][bins];
                inBuf = new float[N];
                tr = new float[N]; ti = new float[N];
                aLr = new float[bins]; aLi = new float[bins];
                aRr = new float[bins]; aRi = new float[bins];
                outL = new float[B]; outR = new float[B];
                float[] re = new float[N];
                float[] im = new float[N];
                for (int p = 0; p < P; p++) {
                    for (int c = 0; c < 2; c++) {
                        java.util.Arrays.fill(re, 0f);
                        java.util.Arrays.fill(im, 0f);
                        int start = p * B;
                        int cnt = Math.min(B, len - start);
                        if (cnt > 0) System.arraycopy(ir[c], start, re, 0, cnt);
                        fft.transform(re, im, false);
                        if (c == 0) {
                            System.arraycopy(re, 0, hLr[p], 0, bins);
                            System.arraycopy(im, 0, hLi[p], 0, bins);
                        } else {
                            System.arraycopy(re, 0, hRr[p], 0, bins);
                            System.arraycopy(im, 0, hRi[p], 0, bins);
                        }
                    }
                }
            }

            static StereoConvolver fromIr(float[][] ir, int blockSize) {
                return new StereoConvolver(ir, blockSize);
            }

            void reset() {
                for (int p = 0; p < P; p++) {
                    java.util.Arrays.fill(xr[p], 0f);
                    java.util.Arrays.fill(xi[p], 0f);
                }
                java.util.Arrays.fill(inBuf, 0f);
                java.util.Arrays.fill(outL, 0f);
                java.util.Arrays.fill(outR, 0f);
                pos = 0;
                fdlPos = 0;
                wetL = 0f;
                wetR = 0f;
            }

            /** Push one input sample; wetL / wetR then hold the reverb output (delayed by one block). */
            void push(float x) {
                inBuf[B + pos] = x;
                wetL = outL[pos];
                wetR = outR[pos];
                pos++;
                if (pos == B) {
                    processBlock();
                    pos = 0;
                }
            }

            private void processBlock() {
                System.arraycopy(inBuf, 0, tr, 0, N);
                java.util.Arrays.fill(ti, 0f);
                fft.transform(tr, ti, false);
                fdlPos = (fdlPos + P - 1) % P;
                System.arraycopy(tr, 0, xr[fdlPos], 0, bins);
                System.arraycopy(ti, 0, xi[fdlPos], 0, bins);

                java.util.Arrays.fill(aLr, 0f); java.util.Arrays.fill(aLi, 0f);
                java.util.Arrays.fill(aRr, 0f); java.util.Arrays.fill(aRi, 0f);
                for (int p = 0; p < P; p++) {
                    int s = fdlPos + p;
                    if (s >= P) s -= P;
                    final float[] ar = xr[s], ai = xi[s];
                    final float[] lr = hLr[p], li = hLi[p], rr = hRr[p], ri = hRi[p];
                    for (int k = 0; k < bins; k++) {
                        float a = ar[k], b = ai[k];
                        aLr[k] += a * lr[k] - b * li[k];
                        aLi[k] += a * li[k] + b * lr[k];
                        aRr[k] += a * rr[k] - b * ri[k];
                        aRi[k] += a * ri[k] + b * rr[k];
                    }
                }
                // Both outputs are real, so one complex inverse FFT gives L (real part) and R (imaginary part):
                // Z = AL + j*AR using the Hermitian symmetry of each spectrum.
                for (int k = 0; k < bins; k++) {
                    tr[k] = aLr[k] - aRi[k];
                    ti[k] = aLi[k] + aRr[k];
                }
                for (int k = 1; k < B; k++) {
                    int m = N - k;
                    // conj(AL[k]) + j*conj(AR[k])
                    tr[m] = aLr[k] + aRi[k];
                    ti[m] = -aLi[k] + aRr[k];
                }
                fft.transform(tr, ti, true);
                System.arraycopy(tr, B, outL, 0, B);
                System.arraycopy(ti, B, outR, 0, B);
                System.arraycopy(inBuf, B, inBuf, 0, B);
            }
        }

        // ---------- Fallback algorithmic reverb (used only if an IR file is missing) ----------
        static final class Freeverb {
            static final int[] COMB_44 = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
            static final int[] AP_44 = {556, 441, 341, 225};
            static final float[] FB = {0.80f, 0.88f, 0.84f, 0.93f};
            static final float[] DAMP = {0.40f, 0.28f, 0.12f, 0.32f};
            static final float[] PRE_MS = {4f, 18f, 0f, 32f};
            static final float[] WET = {2.75f, 2.32f, 2.16f, 2.14f};
            final int sr, nC, nA, preMax;
            final float[][] comb, ap;
            final int[] ci, ai;
            final float[] cLp, pre;
            int pw = 0, preSamp = 0;
            float fb = 0.84f, damp = 0.2f, wet = 1f;

            Freeverb(int sr) {
                this.sr = sr;
                float scale = sr / 44100f;
                nC = COMB_44.length;
                nA = AP_44.length;
                comb = new float[nC][];
                ap = new float[nA][];
                ci = new int[nC];
                ai = new int[nA];
                cLp = new float[nC];
                for (int k = 0; k < nC; k++) comb[k] = new float[Math.max(1, (int) (COMB_44[k] * scale))];
                for (int k = 0; k < nA; k++) ap[k] = new float[Math.max(1, (int) (AP_44[k] * scale))];
                preMax = Math.max((int) (sr * 0.05), 2);
                pre = new float[preMax];
                setType(1);
            }

            void setType(int t) {
                if (t < 0 || t > 3) t = 1;
                fb = FB[t];
                damp = DAMP[t];
                preSamp = Math.min(preMax - 1, (int) (PRE_MS[t] * sr / 1000f));
                wet = WET[t];
            }

            void reset() {
                for (int k = 0; k < nC; k++) { java.util.Arrays.fill(comb[k], 0f); cLp[k] = 0f; }
                for (int k = 0; k < nA; k++) java.util.Arrays.fill(ap[k], 0f);
                java.util.Arrays.fill(pre, 0f);
            }

            float process(float xin) {
                pre[pw] = xin;
                int pr = pw - preSamp;
                if (pr < 0) pr += preMax;
                float rin = pre[pr];
                pw++;
                if (pw >= preMax) pw = 0;
                float x = rin * 0.02f;
                float sum = 0f;
                for (int k = 0; k < nC; k++) {
                    float[] cb = comb[k];
                    int idx = ci[k];
                    float y = cb[idx];
                    float lp = y * (1f - damp) + cLp[k] * damp;
                    if (lp > -1e-15f && lp < 1e-15f) lp = 0f;
                    cLp[k] = lp;
                    cb[idx] = x + lp * fb;
                    idx++;
                    if (idx >= cb.length) idx = 0;
                    ci[k] = idx;
                    sum += y;
                }
                float a = sum;
                for (int k = 0; k < nA; k++) {
                    float[] ab = ap[k];
                    int idx = ai[k];
                    float bo = ab[idx];
                    float o = -a + bo;
                    ab[idx] = a + bo * 0.5f;
                    idx++;
                    if (idx >= ab.length) idx = 0;
                    ai[k] = idx;
                    a = o;
                }
                return a * wet;
            }
        }

        // ---------- Biquad high-pass (low-cut) ----------
        static final class Biquad {
            float b0, b1, b2, a1, a2, z1, z2;

            static Biquad highpass(int sr, double fc, double q) {
                Biquad f = new Biquad();
                double w0 = 2.0 * Math.PI * fc / sr;
                double alpha = Math.sin(w0) / (2.0 * q);
                double cw = Math.cos(w0);
                double a0 = 1.0 + alpha;
                f.b0 = (float) (((1.0 + cw) / 2.0) / a0);
                f.b1 = (float) ((-(1.0 + cw)) / a0);
                f.b2 = f.b0;
                f.a1 = (float) ((-2.0 * cw) / a0);
                f.a2 = (float) ((1.0 - alpha) / a0);
                return f;
            }

            float process(float x) {
                float y = b0 * x + z1;
                z1 = b1 * x - a1 * y + z2;
                z2 = b2 * x - a2 * y;
                return y;
            }
        }

        // ---------- The whole live-monitor signal chain ----------
        // mic (mono) -> [low-cut] -> [noise gate] -> dry + stereo ping-pong echo + IR reverb -> master gain -> soft limiter -> stereo out
        static final class Processor {
            static final float GATE_THR = 0.010f;   // about -40 dBFS
            static final float GATE_FLOOR = 0.06f;  // at most about -24 dB of reduction
            final int sr;
            final float sm;
            float g, e, r, gT, eT, rT;
            boolean lowCut, gateOn, inited = false;
            StereoConvolver conv;
            final Freeverb fv;
            boolean revActive = false;
            // reverb pre-delay + L<->R movement of the reverb
            final float[] pd;
            final int pdLen;
            int pdw = 0, preSamp = 0;
            float panDepthT = 0f, panDepth = 0f, panInc = 0f, panPhase = 0f;
            final int echoLen;
            final float[] eL, eR;
            int ei = 0;
            boolean echoActive = false;
            float lpL = 0f, lpR = 0f;
            final float lpA;
            final Biquad hp;
            float gEnv = 0f, gGain = 1f;
            final float gAtk, gRel, gUp, gDown;

            Processor(int sr) {
                this.sr = sr;
                sm = (float) (1.0 - Math.exp(-1.0 / (0.02 * sr)));
                echoLen = Math.max((int) (sr * 0.32), 1);
                eL = new float[echoLen];
                eR = new float[echoLen];
                pdLen = Math.max((int) (sr * 0.16), 8);
                pd = new float[pdLen];
                lpA = (float) (1.0 - Math.exp(-2.0 * Math.PI * 4000.0 / sr));
                hp = Biquad.highpass(sr, 90.0, 0.7071);
                fv = new Freeverb(sr);
                gAtk = (float) (1.0 - Math.exp(-1.0 / (0.015 * sr))); // average-level detector: noise peaks do not open the gate
                gRel = (float) (1.0 - Math.exp(-1.0 / (0.150 * sr)));
                gUp = (float) (1.0 - Math.exp(-1.0 / (0.005 * sr)));
                gDown = (float) (1.0 - Math.exp(-1.0 / (0.050 * sr)));
            }

            void setConvolver(StereoConvolver c) {
                conv = c;
                if (c != null) c.reset();
                fv.reset();
            }

            void setRevExtras(int preMs, int panPct, float panHz) {
                preSamp = Math.max(0, Math.min(pdLen - 1, (int) (preMs * sr / 1000f)));
                panDepthT = Math.max(0f, Math.min(1f, panPct / 100f));
                panInc = (float) (2.0 * Math.PI * Math.max(0.02f, Math.min(3f, panHz)) / sr);
            }

            void setParams(float gain, float echo, float rev, int type, boolean lowCut, boolean gate) {
                gT = gain; eT = echo; rT = rev;
                if (!inited) { g = gT; e = eT; r = rT; inited = true; }
                this.lowCut = lowCut;
                this.gateOn = gate;
                fv.setType(type);
            }

            private static short limit(float y) {
                float a = Math.abs(y);
                if (a > 0.7f) {
                    float s = 0.7f + 0.3f * (float) Math.tanh((a - 0.7f) / 0.3f);
                    y = (y < 0f) ? -s : s;
                }
                return (short) (y * 32767f);
            }

            /**
             * in: mono 16-bit samples (not modified). out: interleaved stereo for the monitor, needs 2*n entries.
             * rec: optional (may be null) interleaved stereo effect mix for recording: the same sound as the monitor
             * but independent of the monitor volume.
             */
            void process(short[] in, int n, short[] out, short[] rec) {
                final StereoConvolver cv = conv;
                for (int i = 0; i < n; i++) {
                    float x = in[i] * (1f / 32768f);
                    g += (gT - g) * sm;
                    e += (eT - e) * sm;
                    r += (rT - r) * sm;

                    float xh = hp.process(x);
                    if (lowCut) x = xh;

                    if (gateOn) {
                        float a = Math.abs(x);
                        gEnv += ((a > gEnv) ? gAtk : gRel) * (a - gEnv);
                        float q = gEnv / GATE_THR;
                        float tgt = (gEnv >= GATE_THR) ? 1f : Math.max(GATE_FLOOR, q * q);
                        gGain += ((tgt > gGain) ? gUp : gDown) * (tgt - gGain);
                        x *= gGain;
                    } else {
                        gEnv = 0f;
                        gGain = 1f;
                    }

                    // echo (ping-pong, darkened feedback)
                    float echoL = 0f, echoR = 0f;
                    boolean echoOn = e > 0.008f;
                    if (!echoOn && echoActive) {
                        java.util.Arrays.fill(eL, 0f);
                        java.util.Arrays.fill(eR, 0f);
                        lpL = 0f; lpR = 0f;
                        echoActive = false;
                    }
                    if (echoOn) {
                        echoActive = true;
                        float yl = eL[ei], yr = eR[ei];
                        lpL += lpA * (yl - lpL);
                        lpR += lpA * (yr - lpR);
                        float fbk = Math.min(0.62f, 0.2f + e * 0.42f);
                        float nl = x * 0.9f + lpR * fbk;
                        float nr = lpL * fbk;
                        if (nl > -1e-15f && nl < 1e-15f) nl = 0f;
                        if (nr > -1e-15f && nr < 1e-15f) nr = 0f;
                        eL[ei] = nl;
                        eR[ei] = nr;
                        ei++;
                        if (ei >= echoLen) ei = 0;
                        echoL = yl * e * 0.65f;
                        echoR = yr * e * 0.65f;
                    }

                    // reverb (impulse response; algorithmic fallback if no IR)
                    float wl = 0f, wr = 0f;
                    boolean revOn = r > 0.008f;
                    if (!revOn && revActive) {
                        if (cv != null) cv.reset();
                        fv.reset();
                        java.util.Arrays.fill(pd, 0f);
                        revActive = false;
                    }
                    if (revOn) {
                        revActive = true;
                        float xin = x;
                        if (preSamp > 0) {
                            pd[pdw] = x;
                            int rp = pdw - preSamp;
                            if (rp < 0) rp += pdLen;
                            xin = pd[rp];
                            if (++pdw >= pdLen) pdw = 0;
                        }
                        if (cv != null) {
                            cv.push(xin);
                            wl = cv.wetL * r;
                            wr = cv.wetR * r;
                        } else {
                            float w = fv.process(xin) * r;
                            wl = w;
                            wr = w;
                        }
                        panDepth += (panDepthT - panDepth) * sm;
                        if (panDepth > 0.002f) {
                            panPhase += panInc;
                            if (panPhase > 6.2831853f) panPhase -= 6.2831853f;
                            float ang = (panDepth * (float) Math.sin(panPhase) + 1f) * 0.7853982f;
                            wl *= (float) Math.cos(ang) * 1.4142135f;
                            wr *= (float) Math.sin(ang) * 1.4142135f;
                        }
                    }

                    float mixL = x + echoL + wl;
                    float mixR = x + echoR + wr;
                    out[2 * i] = limit(mixL * g);
                    out[2 * i + 1] = limit(mixR * g);
                    if (rec != null) {
                        rec[2 * i] = limit(mixL);
                        rec[2 * i + 1] = limit(mixR);
                    }
                }
            }
        }
    }
    // END MonitorDsp

    // ===== Keep the live monitor alive with the screen off =====
    // A foreground service (type: microphone) + a CPU wake lock. Without it Android silences the mic
    // and freezes the app a few seconds after the screen turns off.
    public static class MonitorService extends android.app.Service {
        private static final String CHANNEL_ID = "swaralay_monitor";
        private android.os.PowerManager.WakeLock wakeLock;

        @Override
        public android.os.IBinder onBind(Intent intent) { return null; }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            try {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                    android.app.NotificationChannel ch = new android.app.NotificationChannel(
                            CHANNEL_ID, "Live monitor", android.app.NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
                Intent open = new Intent(this, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                        this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                android.app.Notification.Builder nb = (Build.VERSION.SDK_INT >= 26)
                        ? new android.app.Notification.Builder(this, CHANNEL_ID)
                        : new android.app.Notification.Builder(this);
                nb.setContentTitle("Swaralay — live monitor on")
                  .setContentText("Microphone monitor is running. Tap to open.")
                  .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                  .setOngoing(true)
                  .setContentIntent(pi);
                android.app.Notification n = nb.build();
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(7101, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                } else {
                    startForeground(7101, n);
                }
            } catch (Exception e) {
                // notification / permission problem: the monitor itself keeps running while the app is open
            }
            try {
                if (wakeLock == null) {
                    android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    if (pm != null) {
                        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Swaralay:monitor");
                        wakeLock.setReferenceCounted(false);
                    }
                }
                if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
            } catch (Exception ignored) {}
            return START_NOT_STICKY;
        }

        @Override
        public void onDestroy() {
            try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
            wakeLock = null;
            try { stopForeground(true); } catch (Exception ignored) {}
            super.onDestroy();
        }
    }

    private void startMonitorService() {
        try {
            Intent i = new Intent(this, MonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception ignored) {}
    }

    private void stopMonitorService() {
        try { stopService(new Intent(this, MonitorService.class)); } catch (Exception ignored) {}
    }

    // ===== Studio-quality PCM recorder state (used by AudioBridge) =====
    private AudioRecord pcmRec;
    private Thread pcmThread;
    private volatile boolean pcmRunning = false;
    private File pcmFile;
    private java.io.FileOutputStream pcmOut;
    private volatile long pcmBytes = 0;
    private int pcmRate = 48000;

    // ===== Native MediaSession (TalkBack two-finger double-tap, headset buttons) =====
    private MediaSession mediaSession;
    private volatile boolean mediaPlaying = false;
    private volatile String mediaTitle = "Swaralay";
    private volatile long mediaPosMs = 0, mediaDurMs = 0;

    // ===== "Open with" support =====
    private boolean pageReady = false;
    private String pendingOpenUri = null;
    private String pendingOpenName = null;

    private void jsMedia(final String cmd) {
        runOnUiThread(() -> {
            try {
                if (webView != null && pageReady) {
                    webView.evaluateJavascript("window.__swaralayMedia&&window.__swaralayMedia('" + cmd + "')", null);
                }
            } catch (Exception ignored) {}
        });
    }

    private void initMediaSession() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || mediaSession != null) return;
            mediaSession = new MediaSession(this, "Swaralay");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { jsMedia("play"); }
                @Override public void onPause() { jsMedia("pause"); }
                @Override public void onStop() { jsMedia("stop"); }
                @Override public void onSkipToNext() { jsMedia("next"); }
                @Override public void onSkipToPrevious() { jsMedia("prev"); }
            });
            refreshMediaSession();
            mediaSession.setActive(true);
        } catch (Exception e) {
            mediaSession = null;
        }
    }

    private void refreshMediaSession() {
        try {
            if (mediaSession == null) return;
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            PlaybackState.Builder pb = new PlaybackState.Builder();
            pb.setActions(actions);
            pb.setState(mediaPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                    mediaPosMs, mediaPlaying ? 1.0f : 0f);
            mediaSession.setPlaybackState(pb.build());
            MediaMetadata.Builder mb = new MediaMetadata.Builder();
            mb.putString(MediaMetadata.METADATA_KEY_TITLE, mediaTitle);
            mb.putString(MediaMetadata.METADATA_KEY_ARTIST, "Swaralay");
            if (mediaDurMs > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, mediaDurMs);
            mediaSession.setMetadata(mb.build());
            if (!mediaSession.isActive()) mediaSession.setActive(true);
        } catch (Exception ignored) {}
    }

    private void handleViewIntent(Intent intent) {
        try {
            if (intent == null) return;
            String action = intent.getAction();
            Uri uri = null;
            if (Intent.ACTION_VIEW.equals(action)) {
                uri = intent.getData();
            } else if (Intent.ACTION_SEND.equals(action)) {
                Object o = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (o instanceof Uri) uri = (Uri) o;
            }
            if (uri == null) return;
            String name = null;
            if ("content".equals(uri.getScheme())) {
                Cursor c = null;
                try {
                    c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                    if (c != null && c.moveToFirst()) name = c.getString(0);
                } catch (Exception ignored) {
                } finally { if (c != null) c.close(); }
            }
            if (name == null || name.length() == 0) name = uri.getLastPathSegment();
            if (name == null || name.length() == 0) name = "audio";
            pendingOpenUri = uri.toString();
            pendingOpenName = name;
            // consume so a recreate does not replay it
            setIntent(new Intent(Intent.ACTION_MAIN));
            dispatchPendingOpen();
        } catch (Exception ignored) {}
    }

    private void dispatchPendingOpen() {
        if (!pageReady || pendingOpenUri == null || webView == null) return;
        final String u = pendingOpenUri, n = pendingOpenName;
        pendingOpenUri = null; pendingOpenName = null;
        webView.post(() -> webView.evaluateJavascript(
                "window.__openExternalAudio&&window.__openExternalAudio("
                        + JSONObject.quote(u) + "," + JSONObject.quote(n) + ")", null));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleViewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        initMediaSession();
        refreshMediaSession();
        try { if (mediaSession != null) mediaSession.setActive(true); } catch (Exception ignored) {}
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        }

        webView.addJavascriptInterface(new AudioBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    Intent intent = params.createIntent();
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(intent, "অডিও সিলেক্ট"), FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Could not open picker", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                // Without this override, the page's navigator.mediaDevices.getUserMedia()
                // call always fails/rejects — even after RECORD_AUDIO is granted in
                // Settings — because WebView blocks mic/camera access from web content
                // by default until the app explicitly grants it here.
                runOnUiThread(() -> {
                    java.util.ArrayList<String> toGrant = new java.util.ArrayList<>();
                    for (String resource : request.getResources()) {
                        if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                                && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO)
                                    == PackageManager.PERMISSION_GRANTED) {
                            toGrant.add(resource);
                        }
                    }
                    if (!toGrant.isEmpty()) {
                        request.grant(toGrant.toArray(new String[0]));
                    } else {
                        request.deny();
                    }
                });
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectEditorPickerJs();
                pageReady = true;
                refreshMediaSession();
                dispatchPendingOpen();
                checkPermissionAndScan();
            }
        });

        initMediaSession();
        handleViewIntent(getIntent());
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void injectEditorPickerJs() {
        String js =
            "(function(){"
          + "if(window.__swaralayPickerReady)return;window.__swaralayPickerReady=true;"
          + "window.__nativeAudioList=window.__nativeAudioList||[];"

          + "window.populateAllAudioLists=function(data){"
          + "  try{"
          + "    var list=(typeof data==='string')?JSON.parse(data):data;"
          + "    if(!Array.isArray(list))list=[];"
          + "    window.__nativeAudioList=list;"
          + "    var trackList=document.getElementById('trackList');"
          + "    if(!trackList)return;"
          + "    trackList.innerHTML='<h4 style=\"margin:0 0 8px;color:#00d2ff;\">📱 ফোনের সব গান ('+list.length+')</h4>';"
          + "    if(!list.length){trackList.innerHTML+='<p style=\"color:#aaa;padding:12px;\">কোনো গান পাওয়া যায়নি। পারমিশন দিন।</p>';return;}"
          + "    if(typeof files==='undefined')window.files=[];"
          + "    window.files=[];"
          + "    list.forEach(function(item,i){"
          + "      var name=item.title||item.name||('গান '+(i+1));"
          + "      var uri=item.uri||item.path||'';"
          + "      window.files.push({name:name,uri:uri,__native:true});"
          + "      var div=document.createElement('div');"
          + "      div.className='track-item';"
          + "      div.setAttribute('role','button');div.tabIndex=0;"
          + "      div.setAttribute('aria-label',name);"
          + "      div.textContent=name+(item.artist?(' — '+item.artist):'');"
          + "      div.onclick=function(){"
          + "        try{"
          + "          if(typeof currentTrackIndex!=='undefined')currentTrackIndex=i;"
          + "          if(typeof audio!=='undefined'&&audio){audio.src=uri;audio.play().catch(function(e){});}"
          + "          var n=document.getElementById('currentTrackName');if(n)n.textContent=name;"
          + "          document.querySelectorAll('.track-item').forEach(function(el){el.classList.remove('playing');});"
          + "          div.classList.add('playing');"
          + "        }catch(e){}"
          + "      };"
          + "      trackList.appendChild(div);"
          + "    });"
          + "  }catch(e){console.error(e);}"
          + "};"

          + "window.__openNativeSongPicker=function(targetInputId){"
          + "  var list=window.__nativeAudioList||[];"
          + "  var old=document.getElementById('__nativePickerOverlay');"
          + "  if(old)old.remove();"
          + "  var ov=document.createElement('div');"
          + "  ov.id='__nativePickerOverlay';"
          + "  ov.setAttribute('role','dialog');"
          + "  ov.setAttribute('aria-label','গান বেছে নিন');"
          + "  ov.style.cssText='position:fixed;inset:0;background:rgba(0,0,0,0.85);z-index:99999;overflow:auto;padding:16px;';"
          + "  var box=document.createElement('div');"
          + "  box.style.cssText='max-width:560px;margin:20px auto;background:#1a1a2e;border-radius:12px;padding:16px;border:1px solid #333;';"
          + "  var h=document.createElement('h3');"
          + "  h.textContent='📱 ফোনের গান থেকে বেছে নিন ('+list.length+')';"
          + "  h.style.cssText='color:#00d2ff;margin:0 0 12px;';"
          + "  box.appendChild(h);"
          + "  var close=document.createElement('button');"
          + "  close.textContent='বন্ধ';close.setAttribute('aria-label','পিকার বন্ধ');"
          + "  close.style.cssText='background:#555;color:#fff;padding:8px 14px;border:none;border-radius:8px;margin-bottom:12px;';"
          + "  close.onclick=function(){ov.remove();};"
          + "  box.appendChild(close);"
          + "  if(!list.length){"
          + "    var p=document.createElement('p');p.style.color='#aaa';"
          + "    p.textContent='গান পাওয়া যায়নি। পারমিশন দিন বা প্লেয়ার ট্যাবে লিস্ট আসুক।';"
          + "    box.appendChild(p);"
          + "  } else {"
          + "    list.forEach(function(item,i){"
          + "      var name=item.title||item.name||('গান '+(i+1));"
          + "      var btn=document.createElement('button');"
          + "      btn.type='button';"
          + "      btn.setAttribute('aria-label',name+' নির্বাচন');"
          + "      btn.textContent='🎵 '+name+(item.artist?(' — '+item.artist):'');"
          + "      btn.style.cssText='display:block;width:100%;text-align:left;padding:12px;margin:6px 0;background:#2a2a40;color:#fff;border:1px solid #444;border-radius:8px;';"
          + "      btn.onclick=function(){window.__assignNativeToInput(targetInputId,item);ov.remove();};"
          + "      box.appendChild(btn);"
          + "    });"
          + "  }"
          + "  ov.appendChild(box);document.body.appendChild(ov);close.focus();"
          + "};"

          + "window.__assignNativeToInput=function(inputId,item){"
          + "  try{"
          + "    if(!window.AndroidBridge||!AndroidBridge.readUriAsBase64){"
          + "      alert('নেটিভ ব্রিজ নেই');return;"
          + "    }"
          + "    var uri=item.uri||item.path;"
          + "    var name=item.title||item.name||'song.mp3';"
          + "    var b64=AndroidBridge.readUriAsBase64(uri);"
          + "    if(!b64||b64.indexOf('ERROR:')===0){alert('ফাইল পড়া যায়নি: '+b64);return;}"
          + "    var bin=atob(b64);"
          + "    var arr=new Uint8Array(bin.length);"
          + "    for(var i=0;i<bin.length;i++)arr[i]=bin.charCodeAt(i);"
          + "    var mime='audio/mpeg';"
          + "    if(/\\.wav$/i.test(name))mime='audio/wav';"
          + "    else if(/\\.ogg$/i.test(name))mime='audio/ogg';"
          + "    else if(/\\.m4a$/i.test(name))mime='audio/mp4';"
          + "    else if(/\\.flac$/i.test(name))mime='audio/flac';"
          + "    var file=new File([arr],name,{type:mime});"
          + "    var dt=new DataTransfer();dt.items.add(file);"
          + "    var input=document.getElementById(inputId);"
          + "    if(!input){alert('ইনপুট পাওয়া যায়নি: '+inputId);return;}"
          + "    input.files=dt.files;"
          + "    input.dispatchEvent(new Event('change',{bubbles:true}));"
          + "    if(typeof onPitchFileSelected==='function'&&inputId==='pitchFile')onPitchFileSelected();"
          + "    alert('সিলেক্ট হয়েছে: '+name);"
          + "  }catch(e){alert('লোড সমস্যা: '+e);console.error(e);}"
          + "};"

          + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void checkPermissionAndScan() {
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        String storagePerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, storagePerm) != PackageManager.PERMISSION_GRANTED) {
            need.add(storagePerm);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS"); // shows the "monitor is on" notification
        }
        if (need.isEmpty()) {
            scanAndPush();
        } else {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean storageOk = true;
            boolean micOk = true;
            for (int i = 0; i < permissions.length; i++) {
                boolean granted = grantResults.length > i && grantResults[i] == PackageManager.PERMISSION_GRANTED;
                if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])) micOk = granted;
                if (Manifest.permission.READ_MEDIA_AUDIO.equals(permissions[i])
                        || Manifest.permission.READ_EXTERNAL_STORAGE.equals(permissions[i])) storageOk = granted;
            }
            if (storageOk) scanAndPush();
            else Toast.makeText(this, "Allow storage permission to list songs", Toast.LENGTH_LONG).show();
            if (!micOk) Toast.makeText(this, "Allow microphone permission to record", Toast.LENGTH_LONG).show();
        }
    }

    private void scanAndPush() {
        new Thread(() -> {
            JSONArray list = scanAudioFiles();
            final String b64 = Base64.encodeToString(
                    list.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            webView.post(() -> webView.evaluateJavascript(
                    "(function(){try{var d=JSON.parse(atob('" + b64 + "'));"
                            + "if(typeof populateAllAudioLists==='function')populateAllAudioLists(d);"
                            + "}catch(e){console.error(e);}})();",
                    null));
        }).start();
    }

    /** Every playable audio file on the phone (songs, recordings, WhatsApp audio, ...) except ringtones / alarms / notifications. */
    private JSONArray scanAudioFiles() {
        JSONArray list = new JSONArray();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        final boolean hasRel = Build.VERSION.SDK_INT >= 29;
        java.util.ArrayList<String> cols = new java.util.ArrayList<>();
        cols.add(MediaStore.Audio.Media._ID);
        cols.add(MediaStore.Audio.Media.DISPLAY_NAME);
        cols.add(MediaStore.Audio.Media.TITLE);
        cols.add(MediaStore.Audio.Media.ARTIST);
        cols.add(MediaStore.Audio.Media.DATE_ADDED);
        cols.add(MediaStore.Audio.Media.SIZE);
        cols.add(MediaStore.Audio.Media.DURATION);
        cols.add(hasRel ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.Audio.Media.DATA);
        String selection = MediaStore.Audio.Media.IS_RINGTONE + "=0 AND "
                + MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND "
                + MediaStore.Audio.Media.IS_ALARM + "=0 AND "
                + MediaStore.Audio.Media.SIZE + ">0";
        String sort = MediaStore.Audio.Media.DISPLAY_NAME + " COLLATE NOCASE ASC";
        try (Cursor c = getContentResolver().query(collection, cols.toArray(new String[0]), selection, null, sort)) {
            if (c == null) return list;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int dateCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED);
            int sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE);
            int durCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION);
            int pathCol = c.getColumnIndex(hasRel ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.Audio.Media.DATA);
            while (c.moveToNext()) {
                try {
                    long id = c.getLong(idCol);
                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    String display = c.getString(nameCol);
                    String title = c.getString(titleCol);
                    if (display == null || display.isEmpty()) display = (title != null && !title.isEmpty()) ? title : ("audio_" + id);
                    String folder = "";
                    if (pathCol >= 0) {
                        String pth = c.getString(pathCol);
                        if (pth != null) {
                            if (!hasRel) {
                                int k = pth.lastIndexOf('/');
                                pth = (k >= 0) ? pth.substring(0, k + 1) : "";
                                if (pth.startsWith("/storage/emulated/0/")) pth = pth.substring("/storage/emulated/0/".length());
                            }
                            folder = pth;
                        }
                    }
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("name", display);
                    o.put("title", (title != null && !title.isEmpty()) ? title : display);
                    String artist = c.getString(artistCol);
                    o.put("artist", (artist != null && !"<unknown>".equals(artist)) ? artist : "");
                    o.put("uri", contentUri.toString());
                    o.put("folder", folder);
                    o.put("date", (dateCol >= 0) ? c.getLong(dateCol) : 0L);
                    o.put("size", (sizeCol >= 0) ? c.getLong(sizeCol) : 0L);
                    o.put("dur", (durCol >= 0) ? c.getLong(durCol) : 0L);
                    list.put(o);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public class AudioBridge {
        /** The Player tab asks for the phone's audio list (asks for permission first if needed). */
        @JavascriptInterface
        public void rescanAudio() {
            runOnUiThread(() -> checkPermissionAndScan());
        }

        /** Stop current TalkBack speech (cannot fully disable TalkBack from an app). */
        @JavascriptInterface
        public void silenceTalkBack() {
            runOnUiThread(() -> {
                try {
                    AccessibilityManager am =
                            (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
                    if (am != null) am.interrupt();
                } catch (Exception ignored) {}
            });
        }

        /** Interrupt TalkBack several times at record start (activation announcement fades). */
        @JavascriptInterface
        public void silenceTalkBackBurst() {
            runOnUiThread(() -> {
                Handler h = new Handler(Looper.getMainLooper());
                Runnable stopSpeech = () -> {
                    try {
                        AccessibilityManager am =
                                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
                        if (am != null) am.interrupt();
                    } catch (Exception ignored) {}
                };
                stopSpeech.run();
                h.postDelayed(stopSpeech, 80);
                h.postDelayed(stopSpeech, 200);
                h.postDelayed(stopSpeech, 400);
                h.postDelayed(stopSpeech, 700);
            });
        }

        @JavascriptInterface
        public void requestRescan() {
            runOnUiThread(() -> checkPermissionAndScan());
        }

        @JavascriptInterface
        public void requestMicPermission() {
            runOnUiThread(() -> {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
                }
            });
        }

        @JavascriptInterface
        public void setMediaState(boolean playing, String title, double posSec, double durSec) {
            mediaPlaying = playing;
            mediaTitle = (title == null || title.length() == 0) ? "Swaralay" : title;
            mediaPosMs = (long) (Math.max(0, posSec) * 1000.0);
            mediaDurMs = (long) (Math.max(0, durSec) * 1000.0);
            runOnUiThread(() -> refreshMediaSession());
        }

        @JavascriptInterface
        public String readUriAsBase64(String uriString) {
            try {
                Uri uri = Uri.parse(uriString);
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) return "ERROR:cannot_open";
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                long max = 40L * 1024 * 1024;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > max) {
                        in.close();
                        return "ERROR:file_too_large";
                    }
                    out.write(buf, 0, n);
                }
                in.close();
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public String getAudioFilesList() {
            return scanAudioFiles().toString();
        }


        // ---- Studio-quality recorder: lossless 16-bit PCM WAV, 48 kHz, no processing ----

        /** Returns "OK" when the PCM recorder started, otherwise null (caller falls back to the AAC recorder). */
        private String startPcmRecording() {
            try {
                int[] rates = {48000, 44100};
                int[] sources = (Build.VERSION.SDK_INT >= 24)
                        ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC,
                                    MediaRecorder.AudioSource.VOICE_RECOGNITION}
                        : new int[]{MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION};
                AudioRecord rec = null;
                int usedRate = 48000;
                outer:
                for (int rate : rates) {
                    int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    if (min <= 0) continue;
                    for (int src : sources) {
                        AudioRecord r = null;
                        try {
                            r = new AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, rate / 2));
                            if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                                rec = r; usedRate = rate;
                                break outer;
                            }
                        } catch (Throwable ignored) {}
                        if (r != null) { try { r.release(); } catch (Throwable ignored) {} }
                    }
                }
                if (rec == null) return null;
                pcmFile = new File(getCacheDir(), "swaralay_rec_" + System.currentTimeMillis() + ".wav");
                pcmOut = new java.io.FileOutputStream(pcmFile);
                pcmOut.write(new byte[44]); // header is filled in when recording stops
                pcmBytes = 0;
                pcmRate = usedRate;
                pcmRec = rec;
                rec.startRecording();
                pcmRunning = true;
                final AudioRecord fr = rec;
                pcmThread = new Thread(() -> {
                    short[] buf = new short[2048];
                    byte[] bytes = new byte[buf.length * 2];
                    while (pcmRunning) {
                        int n = fr.read(buf, 0, buf.length);
                        if (n <= 0) {
                            if (n < 0) break;
                            continue;
                        }
                        for (int i = 0; i < n; i++) {
                            bytes[2 * i] = (byte) (buf[i] & 0xff);
                            bytes[2 * i + 1] = (byte) ((buf[i] >> 8) & 0xff);
                        }
                        try {
                            pcmOut.write(bytes, 0, n * 2);
                            pcmBytes += n * 2L;
                        } catch (Exception e) { break; }
                    }
                }, "swaralay-pcm-rec");
                pcmThread.setPriority(Thread.MAX_PRIORITY);
                pcmThread.start();
                isNativeRecording = true;
                silenceTalkBackBurst();
                return "OK";
            } catch (Throwable t) {
                stopPcmQuiet();
                return null;
            }
        }

        private void stopPcmQuiet() {
            pcmRunning = false;
            try { if (pcmThread != null) pcmThread.join(1500); } catch (Exception ignored) {}
            pcmThread = null;
            if (pcmRec != null) {
                try { pcmRec.stop(); } catch (Throwable ignored) {}
                try { pcmRec.release(); } catch (Throwable ignored) {}
            }
            pcmRec = null;
            try { if (pcmOut != null) pcmOut.close(); } catch (Exception ignored) {}
            pcmOut = null;
        }

        private String finishPcmRecording() {
            try {
                stopPcmQuiet();
                isNativeRecording = false;
                if (pcmFile == null || !pcmFile.exists() || pcmBytes < 2000) return "ERROR:empty_recording";
                byte[] hdr = new byte[44];
                writeWavHeader(hdr, (int) Math.min(pcmBytes, Integer.MAX_VALUE - 64), pcmRate, 1, 16);
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(pcmFile, "rw");
                raf.seek(0);
                raf.write(hdr);
                raf.close();
                byte[] bytes = readFileBytes(pcmFile);
                String name = "Recording_" + System.currentTimeMillis() + ".wav";
                stagedBytes = bytes;
                saveBuffer = null;
                saveFileName = name;
                try { pcmFile.delete(); } catch (Exception ignored) {}
                return "OK:" + name + ":" + bytes.length;
            } catch (Exception e) {
                isNativeRecording = false;
                return "ERROR:" + e.getMessage();
            }
        }

        // ---- Native mic recording (reliable on Android WebView) ----
        @JavascriptInterface
        public synchronized String startNativeRecording() {
            try {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE));
                    return "NEED_PERMISSION";
                }
                if (isNativeRecording) {
                    return "ERROR:already_recording";
                }
                // Live monitor is running: record the RAW mic input from the same
                // AudioRecord (no echo / reverb / gain), instead of opening the mic twice.
                if (monitorRunning) {
                    return startMonitorCapture();
                }
                stopNativeRecorderQuiet();
                if ("OK".equals(startPcmRecording())) return "OK";   // lossless WAV; falls back to AAC below if the device refuses
                nativeRecordFile = new File(getCacheDir(), "swaralay_rec_" + System.currentTimeMillis() + ".m4a");
                // Studio clarity: UNPROCESSED / MIC first (no AGC pumping). Fallback to voice sources.
                int[] sources;
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    sources = new int[]{
                            MediaRecorder.AudioSource.UNPROCESSED,
                            MediaRecorder.AudioSource.MIC,
                            MediaRecorder.AudioSource.VOICE_RECOGNITION
                    };
                } else {
                    sources = new int[]{
                            MediaRecorder.AudioSource.MIC,
                            MediaRecorder.AudioSource.VOICE_RECOGNITION
                    };
                }
                MediaRecorder r = null;
                Exception lastErr = null;
                for (int src : sources) {
                    try {
                        r = new MediaRecorder();
                        r.setAudioSource(src);
                        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                        r.setAudioChannels(1);
                        // High quality default: 48 kHz AAC 256 kbps (studio-friendly on phone mic)
                        r.setAudioEncodingBitRate(256000);
                        r.setAudioSamplingRate(48000);
                        r.setOutputFile(nativeRecordFile.getAbsolutePath());
                        r.prepare();
                        r.start();
                        lastErr = null;
                        break;
                    } catch (Exception e) {
                        lastErr = e;
                        if (r != null) {
                            try { r.release(); } catch (Exception ignored) {}
                            r = null;
                        }
                    }
                }
                if (r == null) {
                    throw (lastErr != null ? lastErr : new Exception("cannot_start_recorder"));
                }
                nativeRecorder = r;
                isNativeRecording = true;
                // Cut off TalkBack "button activated / recording" speech as much as possible
                runOnUiThread(() -> {
                    Handler h = new Handler(Looper.getMainLooper());
                    Runnable stopSpeech = () -> {
                        try {
                            AccessibilityManager am =
                                    (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
                            if (am != null) am.interrupt();
                        } catch (Exception ignored) {}
                    };
                    stopSpeech.run();
                    h.postDelayed(stopSpeech, 80);
                    h.postDelayed(stopSpeech, 200);
                    h.postDelayed(stopSpeech, 450);
                    h.postDelayed(stopSpeech, 800);
                });
                return "OK";
            } catch (Exception e) {
                isNativeRecording = false;
                nativeRecorder = null;
                e.printStackTrace();
                return "ERROR:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String stopNativeRecording() {
            try {
                if (isNativeRecording && recordingViaMonitor) {
                    return finishMonitorCapture();
                }
                if (pcmRec != null || pcmRunning) {
                    return finishPcmRecording();
                }
                if (!isNativeRecording || nativeRecorder == null) {
                    return "ERROR:not_recording";
                }
                try {
                    nativeRecorder.stop();
                } catch (Exception ignored) {}
                try {
                    nativeRecorder.release();
                } catch (Exception ignored) {}
                nativeRecorder = null;
                isNativeRecording = false;

                if (nativeRecordFile == null || !nativeRecordFile.exists() || nativeRecordFile.length() < 100) {
                    return "ERROR:empty_recording";
                }

                // Stage only — do NOT auto-save M4A (user exports MP3 from Settings)
                byte[] bytes = readFileBytes(nativeRecordFile);
                String name = "Recording_" + System.currentTimeMillis() + ".m4a";
                stagedBytes = bytes;
                saveBuffer = null;
                saveFileName = name;
                return "OK:" + name + ":" + bytes.length;
            } catch (Exception e) {
                e.printStackTrace();
                isNativeRecording = false;
                nativeRecorder = null;
                return "ERROR:" + e.getMessage();
            }
        }

        /** After stopNativeRecording, JS can read staged audio in chunks */
        @JavascriptInterface
        public synchronized int getStagedSaveSize() {
            if (stagedBytes != null) return stagedBytes.length;
            return saveBuffer == null ? 0 : saveBuffer.size();
        }

        @JavascriptInterface
        public synchronized String readStagedChunk(int offset, int length) {
            try {
                byte[] all = (stagedBytes != null) ? stagedBytes
                        : (saveBuffer != null ? saveBuffer.toByteArray() : null);
                if (all == null) return "ERROR:none";
                if (offset < 0 || offset >= all.length) return "";
                int end = Math.min(all.length, offset + length);
                byte[] slice = new byte[end - offset];
                System.arraycopy(all, offset, slice, 0, slice.length);
                return Base64.encodeToString(slice, Base64.NO_WRAP);
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized void clearStagedSave() {
            stagedBytes = null;
            saveBuffer = null;
            saveFileName = null;
        }

        private void stopNativeRecorderQuiet() {
            if (pcmRec != null || pcmRunning) { stopPcmQuiet(); isNativeRecording = false; }
            try {
                if (nativeRecorder != null) {
                    try { nativeRecorder.stop(); } catch (Exception ignored) {}
                    try { nativeRecorder.release(); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            nativeRecorder = null;
            isNativeRecording = false;
        }

        private byte[] readFileBytes(File f) throws Exception {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        }

        /** Copy audio track from a video Uri into staged buffer (no quality loss on copy). */
        @JavascriptInterface
        public synchronized String extractAudioFromVideo(String uriString) {
            MediaExtractor extractor = null;
            MediaMuxer muxer = null;
            try {
                if (uriString == null || uriString.isEmpty()) return "ERROR:no_uri";
                Uri uri = Uri.parse(uriString);
                extractor = new MediaExtractor();
                extractor.setDataSource(MainActivity.this, uri, null);
                int audioTrack = -1;
                MediaFormat format = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat f = extractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrack = i;
                        format = f;
                        break;
                    }
                }
                if (audioTrack < 0 || format == null) return "ERROR:no_audio_track";
                extractor.selectTrack(audioTrack);
                File outFile = new File(getCacheDir(), "vid_audio_" + System.currentTimeMillis() + ".m4a");
                muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int dstTrack = muxer.addTrack(format);
                muxer.start();
                ByteBuffer buffer = ByteBuffer.allocate(1024 * 256);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (true) {
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;
                    info.offset = 0;
                    info.size = sampleSize;
                    info.presentationTimeUs = extractor.getSampleTime();
                    info.flags = extractor.getSampleFlags();
                    muxer.writeSampleData(dstTrack, buffer, info);
                    extractor.advance();
                }
                muxer.stop();
                muxer.release();
                muxer = null;
                extractor.release();
                extractor = null;
                byte[] bytes = readFileBytes(outFile);
                try { outFile.delete(); } catch (Exception ignored) {}
                if (bytes.length < 100) return "ERROR:empty_audio";
                String name = "VideoAudio_" + System.currentTimeMillis() + ".m4a";
                stagedBytes = bytes;
                saveBuffer = null;
                saveFileName = name;
                return "OK:" + name + ":" + bytes.length;
            } catch (Exception e) {
                e.printStackTrace();
                return "ERROR:" + e.getMessage();
            } finally {
                try { if (muxer != null) { muxer.release(); } } catch (Exception ignored) {}
                try { if (extractor != null) extractor.release(); } catch (Exception ignored) {}
            }
        }


        // ---- Live mic monitor (AudioRecord -> DSP -> stereo AudioTrack) ----
        // Chain: mic -> [low-cut] -> [noise gate] -> dry + ping-pong echo + IR reverb -> master -> soft limiter.
        // Reverb uses the ir-room / ir-hall / ir-plate / ir-cathedral .wav files from the assets folder
        // (same files as the FX panel). If a file is missing a built-in reverb is used instead.
        private int reverbTypeId(String t) {
            if ("room".equals(t)) return 0;
            if ("plate".equals(t)) return 2;
            if ("cathedral".equals(t)) return 3;
            return 1; // hall
        }

        private String sourceName(int src) {
            if (src == MediaRecorder.AudioSource.MIC) return "MIC";
            if (src == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "VOICE_RECOGNITION";
            if (src == MediaRecorder.AudioSource.VOICE_COMMUNICATION) return "VOICE_COMMUNICATION";
            return "DEFAULT";
        }

        /** Switch reverb type; works while the monitor is running (new impulse response loads in the background). */
        @JavascriptInterface
        public void setNativeMonitorReverbType(String type) {
            final int id = reverbTypeId(type);
            monitorRevType = id;
            if (!monitorRunning) return;
            final int tok = convToken.incrementAndGet();
            final int sr = monitorSampleRate;
            new Thread(() -> {
                MonitorDsp.StereoConvolver c = buildConvolver(id, sr);
                if (tok != convToken.get()) return; // a newer choice arrived meanwhile
                monitorIrOk = (c != null);
                pendingConv.set(new ConvMsg(c));
            }, "SwaralayIrLoad").start();
        }

        /**
         * lowCut   = cut rumble / breath thumps below ~90 Hz.
         * gate     = reduce hiss while you are silent.
         * micMode  = "clear" (raw MIC) or "voice" (phone voice filtering + noise suppressor).
         * safeLatency = bigger buffers, for crackling on slow phones / Bluetooth.
         * recordFx = a recording that starts while the monitor is on includes the monitor's echo / reverb
         *            (stereo). Off = only the raw mic input is recorded.
         * Returns "NEEDS_RESTART" when micMode / safeLatency changed while the monitor is running.
         */
        /** Reverb pre-delay (ms, 0..120) and L<->R movement of the reverb (depth %, speed Hz). */
        @JavascriptInterface
        public void setNativeMonitorRevExtras(int preMs, int panPct, double panHz) {
            monitorRevPreMs = Math.max(0, Math.min(120, preMs));
            monitorRevPanPct = Math.max(0, Math.min(100, panPct));
            monitorRevPanHz = (float) Math.max(0.02, Math.min(3.0, panHz));
        }

        @JavascriptInterface
        public String setNativeMonitorOptions(boolean lowCut, boolean gate, String micMode, boolean safeLatency, boolean recordFx) {
            optRecordFx = recordFx;
            optLowCut = lowCut;
            optGate = gate;
            String mode = "voice".equals(micMode) ? "voice" : "clear";
            optMicMode = mode;
            optSafeLatency = safeLatency;
            if (monitorRunning && (!mode.equals(activeMicMode) || safeLatency != activeSafeLatency)) {
                return "NEEDS_RESTART";
            }
            return "OK";
        }

        /** Small JSON status for the page: sample rate, mic source, whether the IR reverb file was found. */
        @JavascriptInterface
        public String getNativeMonitorInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("on", monitorRunning);
                o.put("sr", monitorSampleRate);
                o.put("src", monitorSourceName);
                o.put("irOk", monitorIrOk);
                o.put("type", IR_NAMES[Math.max(0, Math.min(IR_NAMES.length - 1, monitorRevType))]);
                o.put("ns", monitorNs != null);
                o.put("mode", activeMicMode);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public synchronized String startNativeMonitor(double gainPercent, double echoPercent, double reverbPercent) {
            try {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE));
                    return "NEED_PERMISSION";
                }
                if (monitorRunning) {
                    applyMonitorLevels(gainPercent, echoPercent, reverbPercent);
                    return "OK:already_on";
                }
                if (isNativeRecording) {
                    // Mic is already used by a normal recording: start the monitor first, then record.
                    return "ERROR:record_active";
                }
                stopNativeMonitorInternal();
                applyMonitorLevels(gainPercent, echoPercent, reverbPercent);

                final boolean voiceMode = "voice".equals(optMicMode);
                final boolean safe = optSafeLatency;
                final int channelIn = AudioFormat.CHANNEL_IN_MONO;
                final int channelOut = AudioFormat.CHANNEL_OUT_STEREO;
                final int encoding = AudioFormat.ENCODING_PCM_16BIT;

                int[] sources;
                if (voiceMode) {
                    sources = new int[] {
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                            MediaRecorder.AudioSource.MIC,
                            MediaRecorder.AudioSource.DEFAULT
                    };
                } else {
                    // Clear: no voice-call AEC / noise suppression / AGC colouring
                    sources = new int[] {
                            MediaRecorder.AudioSource.MIC,
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                            MediaRecorder.AudioSource.DEFAULT
                    };
                }
                int[] rates = new int[] {48000, 44100};
                AudioRecord rec = null;
                int sr = 0;
                int minPlayChosen = 0;
                int usedSource = MediaRecorder.AudioSource.DEFAULT;
                Exception last = null;
                for (int rate : rates) {
                    int minRec = AudioRecord.getMinBufferSize(rate, channelIn, encoding);
                    int minPlay = AudioTrack.getMinBufferSize(rate, channelOut, encoding);
                    if (minRec <= 0 || minPlay <= 0) continue;
                    int b = Math.max(minRec, minPlay) * (safe ? 4 : 2);
                    for (int src : sources) {
                        try {
                            AudioRecord r = new AudioRecord(src, rate, channelIn, encoding, b);
                            if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                                rec = r;
                                sr = rate;
                                minPlayChosen = minPlay;
                                usedSource = src;
                                break;
                            }
                            r.release();
                        } catch (Exception e) {
                            last = e;
                        }
                    }
                    if (rec != null) break;
                }
                if (rec == null) {
                    return "ERROR:cannot_open_mic:" + (last != null ? last.getMessage() : "unknown");
                }

                releaseMonitorEffects();
                if (voiceMode) {
                    try {
                        if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                            monitorNs = android.media.audiofx.NoiseSuppressor.create(rec.getAudioSessionId());
                            if (monitorNs != null) monitorNs.setEnabled(true);
                        }
                    } catch (Throwable ignored) {
                        monitorNs = null;
                    }
                }

                AudioTrack track;
                int trackBuf = safe ? Math.max(minPlayChosen * 4, 16384) : Math.max(minPlayChosen * 2, 2048);
                if (Build.VERSION.SDK_INT >= 26) {
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(encoding)
                                    .setSampleRate(sr)
                                    .setChannelMask(channelOut)
                                    .build())
                            .setBufferSizeInBytes(trackBuf)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setPerformanceMode(safe ? AudioTrack.PERFORMANCE_MODE_NONE
                                    : AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                            .build();
                } else {
                    track = new AudioTrack(AudioManager.STREAM_MUSIC, sr, channelOut, encoding,
                            trackBuf, AudioTrack.MODE_STREAM);
                }
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    try { rec.release(); } catch (Exception ignored) {}
                    releaseMonitorEffects();
                    return "ERROR:cannot_open_speaker";
                }

                final MonitorDsp.Processor proc = new MonitorDsp.Processor(sr);
                MonitorDsp.StereoConvolver c0 = buildConvolver(monitorRevType, sr);
                proc.setConvolver(c0);
                monitorIrOk = (c0 != null);
                pendingConv.set(null);
                convToken.incrementAndGet();
                activeMicMode = voiceMode ? "voice" : "clear";
                activeSafeLatency = safe;
                monitorSourceName = sourceName(usedSource);

                monitorRecord = rec;
                monitorTrack = track;
                monitorSampleRate = sr;
                monitorRunning = true;
                startMonitorService();

                runOnUiThread(() -> {
                    try {
                        if (audioManager == null) {
                            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                        }
                        if (audioManager != null) {
                            audioManager.setMode(AudioManager.MODE_NORMAL);
                            audioManager.setSpeakerphoneOn(false);
                        }
                    } catch (Exception ignored) {}
                });

                final AudioRecord fRec = rec;
                final AudioTrack fTrack = track;
                final int blk = Math.max(safe ? sr / 40 : sr / 100, 240);
                monitorThread = new Thread(() -> {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                    } catch (Exception ignored) {}
                    short[] buffer = new short[blk];
                    short[] outBuf = new short[blk * 2];
                    short[] fxBuf = new short[blk * 2];
                    byte[] rawBytes = new byte[blk * 4]; // big enough for stereo FX recording too
                    try {
                        fRec.startRecording();
                        fTrack.play();
                        while (monitorRunning) {
                            int n = fRec.read(buffer, 0, blk);
                            if (n <= 0) {
                                if (n < 0) {
                                    try { Thread.sleep(5); } catch (InterruptedException ie) { break; }
                                }
                                continue;
                            }
                            ConvMsg msg = pendingConv.getAndSet(null);
                            if (msg != null) proc.setConvolver(msg.conv);
                            proc.setParams(monitorGain, monitorEcho, monitorReverb, monitorRevType,
                                    optLowCut, optGate);
                            proc.setRevExtras(monitorRevPreMs, monitorRevPanPct, monitorRevPanHz);
                            final boolean capNow = monitorCapturing;
                            final boolean fxNow = capNow && captureFx;
                            proc.process(buffer, n, outBuf, fxNow ? fxBuf : null);
                            fTrack.write(outBuf, 0, n * 2);

                            if (capNow) {
                                int bytes;
                                if (fxNow) {
                                    // effect mix (same sound as the monitor, stereo, independent of monitor volume)
                                    for (int i = 0; i < n * 2; i++) {
                                        short s = fxBuf[i];
                                        rawBytes[2 * i] = (byte) (s & 0xff);
                                        rawBytes[2 * i + 1] = (byte) ((s >> 8) & 0xff);
                                    }
                                    bytes = n * 4;
                                } else {
                                    // RAW mic samples (buffer is never modified by the DSP)
                                    for (int i = 0; i < n; i++) {
                                        short s = buffer[i];
                                        rawBytes[2 * i] = (byte) (s & 0xff);
                                        rawBytes[2 * i + 1] = (byte) ((s >> 8) & 0xff);
                                    }
                                    bytes = n * 2;
                                }
                                synchronized (captureLock) {
                                    if (captureOut != null) {
                                        try {
                                            captureOut.write(rawBytes, 0, bytes);
                                        } catch (Exception ignored) {}
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    } finally {
                        try { fRec.stop(); } catch (Exception ignored) {}
                        try { fTrack.stop(); } catch (Exception ignored) {}
                    }
                }, "SwaralayMonitor");
                monitorThread.start();
                return "OK";
            } catch (Exception e) {
                e.printStackTrace();
                stopNativeMonitorInternal();
                return "ERROR:" + e.getMessage();
            }
        }

        private void applyMonitorLevels(double gainPercent, double echoPercent, double reverbPercent) {
            float vol = (float) Math.max(0, Math.min(150, gainPercent)) / 100f;
            monitorGain = Math.max(0.15f, Math.min(3.2f, vol * 1.9f));
            float e = (float) Math.max(0, Math.min(100, echoPercent)) / 100f;
            float r = (float) Math.max(0, Math.min(100, reverbPercent)) / 100f;
            monitorEcho = (e < 0.01f) ? 0f : (0.2f + e * 0.8f);
            monitorReverb = (r < 0.01f) ? 0f : (0.15f + r * 0.85f);
        }

        @JavascriptInterface
        public synchronized void setNativeMonitorLevels(double gainPercent, double echoPercent, double reverbPercent) {
            applyMonitorLevels(gainPercent, echoPercent, reverbPercent);
        }

        @JavascriptInterface
        public synchronized String stopNativeMonitor() {
            stopNativeMonitorInternal();
            return "OK";
        }

        private void stopNativeMonitorInternal() {
            monitorRunning = false;
            stopMonitorService();
            try {
                if (monitorThread != null) {
                    monitorThread.join(500);
                }
            } catch (Exception ignored) {}
            monitorThread = null;
            try {
                if (monitorRecord != null) {
                    try { monitorRecord.stop(); } catch (Exception ignored) {}
                    try { monitorRecord.release(); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            monitorRecord = null;
            try {
                if (monitorTrack != null) {
                    try { monitorTrack.stop(); } catch (Exception ignored) {}
                    try { monitorTrack.release(); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            monitorTrack = null;
            releaseMonitorEffects();
            pendingConv.set(null);
            convToken.incrementAndGet();
            monitorIrOk = false;
        }

        // ---- Record while the live monitor is running ----
        // The monitor thread already owns the microphone, so it hands us the samples:
        //  * default: the RAW mic input (before gain / echo / reverb), mono
        //  * "Record with effects": the monitor's effect mix (voice + echo + reverb), stereo
        private String startMonitorCapture() {
            try {
                synchronized (captureLock) {
                    captureFile = new File(getCacheDir(), "swaralay_cap_" + System.currentTimeMillis() + ".pcm");
                    captureOut = new java.io.BufferedOutputStream(new java.io.FileOutputStream(captureFile), 64 * 1024);
                }
                captureSampleRate = monitorSampleRate;
                captureFx = optRecordFx;
                captureChannels = captureFx ? 2 : 1;
                recordingViaMonitor = true;
                isNativeRecording = true;
                monitorCapturing = true;
                runOnUiThread(() -> {
                    Handler h = new Handler(Looper.getMainLooper());
                    Runnable stopSpeech = () -> {
                        try {
                            AccessibilityManager am =
                                    (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
                            if (am != null) am.interrupt();
                        } catch (Exception ignored) {}
                    };
                    stopSpeech.run();
                    h.postDelayed(stopSpeech, 80);
                    h.postDelayed(stopSpeech, 250);
                    h.postDelayed(stopSpeech, 600);
                });
                return "OK";
            } catch (Exception e) {
                monitorCapturing = false;
                recordingViaMonitor = false;
                isNativeRecording = false;
                return "ERROR:" + e.getMessage();
            }
        }

        private String finishMonitorCapture() {
            try {
                monitorCapturing = false;
                synchronized (captureLock) {
                    if (captureOut != null) {
                        try { captureOut.flush(); } catch (Exception ignored) {}
                        try { captureOut.close(); } catch (Exception ignored) {}
                        captureOut = null;
                    }
                }
                long len = (captureFile != null && captureFile.exists()) ? captureFile.length() : 0L;
                recordingViaMonitor = false;
                isNativeRecording = false;
                if (len < 200L || len > 500L * 1024 * 1024) {
                    if (captureFile != null) { try { captureFile.delete(); } catch (Exception ignored) {} }
                    captureFile = null;
                    return len < 200L ? "ERROR:empty_recording" : "ERROR:recording_too_large";
                }
                // read the PCM straight into the WAV buffer (no second copy)
                byte[] wav = new byte[44 + (int) len];
                FileInputStream pin = new FileInputStream(captureFile);
                try {
                    int off = 44;
                    while (off < wav.length) {
                        int r = pin.read(wav, off, wav.length - off);
                        if (r < 0) break;
                        off += r;
                    }
                } finally {
                    try { pin.close(); } catch (Exception ignored) {}
                }
                try { captureFile.delete(); } catch (Exception ignored) {}
                captureFile = null;
                writeWavHeader(wav, (int) len, captureSampleRate, captureChannels, 16);
                String name = (captureFx ? "Recording_FX_" : "Recording_") + System.currentTimeMillis() + ".wav";
                stagedBytes = wav;
                saveBuffer = null;
                saveFileName = name;
                return "OK:" + name + ":" + wav.length;
            } catch (Exception e) {
                e.printStackTrace();
                recordingViaMonitor = false;
                isNativeRecording = false;
                return "ERROR:" + e.getMessage();
            }
        }

        private void writeWavHeader(byte[] h, int dataLen, int sampleRate, int channels, int bits) {
            int byteRate = sampleRate * channels * bits / 8;
            int total = dataLen + 36;
            h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
            h[4] = (byte) (total & 0xff); h[5] = (byte) ((total >> 8) & 0xff);
            h[6] = (byte) ((total >> 16) & 0xff); h[7] = (byte) ((total >> 24) & 0xff);
            h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
            h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
            h[16] = 16; h[17] = 0; h[18] = 0; h[19] = 0;
            h[20] = 1; h[21] = 0;
            h[22] = (byte) channels; h[23] = 0;
            h[24] = (byte) (sampleRate & 0xff); h[25] = (byte) ((sampleRate >> 8) & 0xff);
            h[26] = (byte) ((sampleRate >> 16) & 0xff); h[27] = (byte) ((sampleRate >> 24) & 0xff);
            h[28] = (byte) (byteRate & 0xff); h[29] = (byte) ((byteRate >> 8) & 0xff);
            h[30] = (byte) ((byteRate >> 16) & 0xff); h[31] = (byte) ((byteRate >> 24) & 0xff);
            h[32] = (byte) (channels * bits / 8); h[33] = 0;
            h[34] = (byte) bits; h[35] = 0;
            h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
            h[40] = (byte) (dataLen & 0xff); h[41] = (byte) ((dataLen >> 8) & 0xff);
            h[42] = (byte) ((dataLen >> 16) & 0xff); h[43] = (byte) ((dataLen >> 24) & 0xff);
        }

        // ---- Save audio from WebView (chunked, avoids Binder 1MB limit) ----
        private transient ByteArrayOutputStream saveBuffer = null;
        private transient String saveFileName = null;

        @JavascriptInterface
        public synchronized String startSave(String filename) {
            try {
                if (filename == null || filename.trim().isEmpty()) filename = "swaralay_audio.wav";
                filename = filename.replaceAll("[\\/:*?\"<>|]", "_").trim();
                if (!filename.toLowerCase().endsWith(".wav") && !filename.toLowerCase().endsWith(".webm")
                        && !filename.toLowerCase().endsWith(".m4a") && !filename.toLowerCase().endsWith(".mp3")) {
                    filename = filename + ".wav";
                }
                saveFileName = filename;
                saveBuffer = new ByteArrayOutputStream();
                return "OK";
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String appendSaveChunk(String base64Chunk) {
            try {
                if (saveBuffer == null) return "ERROR:not_started";
                if (base64Chunk == null || base64Chunk.isEmpty()) return "OK";
                byte[] data = Base64.decode(base64Chunk, Base64.DEFAULT);
                saveBuffer.write(data);
                return "OK";
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String finishSave() {
            ByteArrayOutputStream buf = saveBuffer;
            String name = saveFileName;
            saveBuffer = null;
            saveFileName = null;
            if (buf == null || name == null) return "ERROR:not_started";
            try {
                byte[] bytes = buf.toByteArray();
                if (bytes.length == 0) return "ERROR:empty";

                String mime = "audio/wav";
                String lower = name.toLowerCase();
                if (lower.endsWith(".webm")) mime = "audio/webm";
                else if (lower.endsWith(".m4a")) mime = "audio/mp4";
                else if (lower.endsWith(".mp3")) mime = "audio/mpeg";

                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Audio.Media.MIME_TYPE, mime);
                values.put(MediaStore.Audio.Media.IS_PENDING, 1);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Swaralay");
                }

                Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                        return "ERROR:cannot_create_file";
                    }
                    // Fallback: Downloads (API 29+)
                    ContentValues v2 = new ContentValues();
                    v2.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v2.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v2.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Swaralay");
                    v2.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v2);
                    if (uri == null) return "ERROR:cannot_create_file";
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) return "ERROR:cannot_open_stream";
                        out.write(bytes);
                        out.flush();
                    }
                    v2.clear();
                    v2.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, v2, null, null);
                    final String msg = "Saved: Download/Swaralay/" + name;
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
                    return "OK:" + msg;
                }

                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) return "ERROR:cannot_open_stream";
                    out.write(bytes);
                    out.flush();
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, done, null, null);
                }
                final String msg = "Saved: Music/Swaralay/" + name;
                runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
                // Refresh native song list
                runOnUiThread(() -> checkPermissionAndScan());
                return "OK:" + msg;
            } catch (Exception e) {
                e.printStackTrace();
                return "ERROR:" + e.getMessage();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                results = new Uri[n];
                for (int i = 0; i < n; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView == null) { super.onBackPressed(); return; }
        // The page gets the first chance: leave the full-screen player / a folder, then fall back to normal behaviour.
        webView.evaluateJavascript(
                "(function(){try{return !!(window.__swaralayBack&&window.__swaralayBack());}catch(e){return false;}})()",
                value -> {
                    if ("true".equals(value)) return;
                    if (webView.canGoBack()) webView.goBack();
                    else MainActivity.super.onBackPressed();
                });
    }

    @Override
    protected void onDestroy() {
        try {
            // stop monitor if Activity is destroyed
            monitorRunning = false;
            if (monitorRecord != null) {
                try { monitorRecord.stop(); } catch (Exception ignored) {}
                try { monitorRecord.release(); } catch (Exception ignored) {}
            }
            if (monitorTrack != null) {
                try { monitorTrack.stop(); } catch (Exception ignored) {}
                try { monitorTrack.release(); } catch (Exception ignored) {}
            }
            releaseMonitorEffects();
        } catch (Exception ignored) {}
        stopMonitorService();
        try { pcmRunning = false; if (pcmRec != null) { pcmRec.stop(); pcmRec.release(); } } catch (Throwable ignored) {}
        try { if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); mediaSession = null; } } catch (Exception ignored) {}
        super.onDestroy();
    }
}

