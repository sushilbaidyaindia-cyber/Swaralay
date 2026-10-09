package com.sushil.swaralay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
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
    private volatile int monitorRevType = 1; // 0 room, 1 hall, 2 plate, 3 cathedral
    private volatile boolean monitorCapturing = false;
    private boolean recordingViaMonitor = false;
    private final Object captureLock = new Object();
    private java.io.BufferedOutputStream captureOut = null;
    private File captureFile = null;
    private int captureSampleRate = 44100;
    private int monitorSampleRate = 44100;
    private byte[] stagedBytes = null;

    // Freeverb-style comb / all-pass delay lengths (samples @ 44.1 kHz)
    private static final int[] COMB_44 = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] AP_44 = {556, 441, 341, 225};
    // per reverb type: feedback, damping, pre-delay (ms), wet gain
    private static final float[] REV_FB   = {0.80f, 0.88f, 0.84f, 0.93f};
    private static final float[] REV_DAMP = {0.40f, 0.28f, 0.12f, 0.32f};
    private static final float[] REV_PRE  = {4f, 18f, 0f, 32f};
    private static final float[] REV_WET  = {1.6f, 1.3f, 1.4f, 1.1f};

    /** Smooth limiter: linear below 70%, soft knee above, never exceeds full scale. */
    private static short softLimit(float x) {
        float y = x / 32768f;
        float a = Math.abs(y);
        if (a > 0.7f) {
            float s = 0.7f + 0.3f * (float) Math.tanh((a - 0.7f) / 0.3f);
            y = (y < 0f) ? -s : s;
        }
        return (short) (y * 32767f);
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
                checkPermissionAndScan();
            }
        });

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

    private JSONArray scanAudioFiles() {
        JSONArray list = new JSONArray();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATE_ADDED
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";
        String sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";
        try (Cursor c = getContentResolver().query(collection, projection, selection, null, sort)) {
            if (c == null) return list;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int dateCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED);
            while (c.moveToNext()) {
                long id = c.getLong(idCol);
                Uri contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                try {
                    JSONObject o = new JSONObject();
                    String title = c.getString(titleCol);
                    if (title == null || title.isEmpty()) title = c.getString(nameCol);
                    o.put("id", id);
                    o.put("name", c.getString(nameCol));
                    o.put("title", title != null ? title : "Unknown");
                    o.put("artist", c.getString(artistCol) != null ? c.getString(artistCol) : "");
                    o.put("uri", contentUri.toString());
                    o.put("path", contentUri.toString());
                    long dateAdded = (dateCol >= 0) ? c.getLong(dateCol) : 0L;
                    o.put("dateAdded", dateAdded);
                    o.put("date", dateAdded);
                    list.put(o);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public class AudioBridge {
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
        public boolean hasMicPermission() {
            return ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
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

        @JavascriptInterface
        public synchronized boolean isNativeRecording() {
            return isNativeRecording;
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

        private String writeBytesToMusic(byte[] bytes, String name, String mime) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Audio.Media.MIME_TYPE, mime);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Swaralay");
                    values.put(MediaStore.Audio.Media.IS_PENDING, 1);
                }
                Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return "ERROR:cannot_create_file";
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
                return "OK:" + msg;
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
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


        // ---- Live mic monitor (AudioRecord -> DSP -> AudioTrack) ----
        // Echo + Freeverb-style reverb (8 combs + 4 all-pass), soft limiter, low latency.
        private int reverbTypeId(String t) {
            if ("room".equals(t)) return 0;
            if ("plate".equals(t)) return 2;
            if ("cathedral".equals(t)) return 3;
            return 1; // hall
        }

        @JavascriptInterface
        public void setNativeMonitorReverbType(String type) {
            monitorRevType = reverbTypeId(type);
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

                final int channelIn = AudioFormat.CHANNEL_IN_MONO;
                final int channelOut = AudioFormat.CHANNEL_OUT_MONO;
                final int encoding = AudioFormat.ENCODING_PCM_16BIT;

                // MIC first (no voice-call AEC/NS/AGC colouring), then fallbacks.
                int[] sources = new int[] {
                        MediaRecorder.AudioSource.MIC,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.DEFAULT
                };
                int[] rates = new int[] {48000, 44100};
                AudioRecord rec = null;
                int sr = 0;
                int recBuf = 0;
                int minPlayChosen = 0;
                Exception last = null;
                for (int rate : rates) {
                    int minRec = AudioRecord.getMinBufferSize(rate, channelIn, encoding);
                    int minPlay = AudioTrack.getMinBufferSize(rate, channelOut, encoding);
                    if (minRec <= 0 || minPlay <= 0) continue;
                    int b = Math.max(minRec, minPlay) * 2;
                    for (int src : sources) {
                        try {
                            AudioRecord r = new AudioRecord(src, rate, channelIn, encoding, b);
                            if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                                rec = r;
                                sr = rate;
                                recBuf = b;
                                minPlayChosen = minPlay;
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

                AudioTrack track;
                int trackBuf = Math.max(minPlayChosen * 2, 2048);
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
                            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                            .build();
                } else {
                    track = new AudioTrack(AudioManager.STREAM_MUSIC, sr, channelOut, encoding,
                            trackBuf, AudioTrack.MODE_STREAM);
                }
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    rec.release();
                    return "ERROR:cannot_open_speaker";
                }

                monitorRecord = rec;
                monitorTrack = track;
                monitorSampleRate = sr;
                monitorRunning = true;

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
                final int fSr = sr;
                monitorThread = new Thread(() -> {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                    } catch (Exception ignored) {}
                    final int blk = Math.max(fSr / 100, 240); // ~10 ms blocks = low latency
                    short[] buffer = new short[blk];
                    byte[] rawBytes = new byte[blk * 2];
                    final float scale = fSr / 44100f;

                    // Echo (~320 ms single repeat with feedback)
                    final int echoLen = Math.max((int) (fSr * 0.32), 1);
                    float[] echoBuf = new float[echoLen];
                    int ei = 0;

                    // Reverb: pre-delay -> 8 parallel damped combs -> 4 series all-pass
                    final int nC = COMB_44.length;
                    final int nA = AP_44.length;
                    float[][] comb = new float[nC][];
                    int[] ci = new int[nC];
                    float[] cLp = new float[nC];
                    for (int k = 0; k < nC; k++) comb[k] = new float[Math.max(1, (int) (COMB_44[k] * scale))];
                    float[][] ap = new float[nA][];
                    int[] ai = new int[nA];
                    for (int k = 0; k < nA; k++) ap[k] = new float[Math.max(1, (int) (AP_44[k] * scale))];
                    final int preMax = Math.max((int) (fSr * 0.05), 2);
                    float[] pre = new float[preMax];
                    int pw = 0;

                    boolean revActive = false;
                    boolean echoActive = false;
                    int curType = -1;
                    float fb = 0.84f, damp = 0.2f, wetTypeGain = 1.4f;
                    int preSamp = 0;

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
                            final float g = monitorGain;
                            final float eAmt = monitorEcho;
                            final float rAmt = monitorReverb;
                            final int ty = monitorRevType;
                            final boolean cap = monitorCapturing;

                            if (ty != curType) {
                                curType = ty;
                                fb = REV_FB[ty];
                                damp = REV_DAMP[ty];
                                preSamp = Math.min(preMax - 1, (int) (REV_PRE[ty] * fSr / 1000f));
                                wetTypeGain = REV_WET[ty];
                            }
                            final boolean revOn = rAmt > 0.008f;
                            final boolean echoOn = eAmt > 0.008f;

                            // When an effect is switched off, wipe its memory so old sound
                            // never "comes back" when it is switched on again.
                            if (!revOn && revActive) {
                                for (int k = 0; k < nC; k++) { java.util.Arrays.fill(comb[k], 0f); cLp[k] = 0f; }
                                for (int k = 0; k < nA; k++) java.util.Arrays.fill(ap[k], 0f);
                                java.util.Arrays.fill(pre, 0f);
                                revActive = false;
                            }
                            if (revOn) revActive = true;
                            if (!echoOn && echoActive) {
                                java.util.Arrays.fill(echoBuf, 0f);
                                echoActive = false;
                            }
                            if (echoOn) echoActive = true;

                            for (int i = 0; i < n; i++) {
                                final short rawS = buffer[i];
                                final float dry = rawS;
                                if (cap) {
                                    rawBytes[2 * i] = (byte) (rawS & 0xff);
                                    rawBytes[2 * i + 1] = (byte) ((rawS >> 8) & 0xff);
                                }
                                float out = dry * g;

                                if (echoOn) {
                                    float es = echoBuf[ei];
                                    out += es * eAmt;
                                    echoBuf[ei] = dry * 0.9f + es * (0.2f + eAmt * 0.5f);
                                    ei++;
                                    if (ei >= echoLen) ei = 0;
                                }

                                if (revOn) {
                                    pre[pw] = dry;
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
                                        cLp[k] = y * (1f - damp) + cLp[k] * damp;
                                        cb[idx] = x + cLp[k] * fb;
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
                                    out += a * rAmt * wetTypeGain;
                                }

                                buffer[i] = softLimit(out);
                            }
                            fTrack.write(buffer, 0, n);

                            if (cap) {
                                synchronized (captureLock) {
                                    if (captureOut != null) {
                                        try {
                                            captureOut.write(rawBytes, 0, n * 2);
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

        @JavascriptInterface
        public synchronized boolean isNativeMonitorOn() {
            return monitorRunning;
        }

        private void stopNativeMonitorInternal() {
            monitorRunning = false;
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
        }

        // ---- Record RAW mic input while the live monitor is running ----
        // The monitor thread already owns the microphone, so it hands us the untouched
        // input samples (before gain / echo / reverb). Output of the monitor is never recorded.
        private String startMonitorCapture() {
            try {
                synchronized (captureLock) {
                    captureFile = new File(getCacheDir(), "swaralay_cap_" + System.currentTimeMillis() + ".pcm");
                    captureOut = new java.io.BufferedOutputStream(new java.io.FileOutputStream(captureFile), 64 * 1024);
                }
                captureSampleRate = monitorSampleRate;
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
                byte[] pcm = new byte[0];
                if (captureFile != null && captureFile.exists()) {
                    pcm = readFileBytes(captureFile);
                    try { captureFile.delete(); } catch (Exception ignored) {}
                }
                captureFile = null;
                recordingViaMonitor = false;
                isNativeRecording = false;
                if (pcm.length < 200) return "ERROR:empty_recording";

                byte[] wav = new byte[44 + pcm.length];
                writeWavHeader(wav, pcm.length, captureSampleRate, 1, 16);
                System.arraycopy(pcm, 0, wav, 44, pcm.length);
                String name = "Recording_" + System.currentTimeMillis() + ".wav";
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
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
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
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}

