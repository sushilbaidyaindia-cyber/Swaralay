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
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
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
                    Toast.makeText(MainActivity.this, "পিকার খোলা যায়নি", Toast.LENGTH_SHORT).show();
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
            else Toast.makeText(this, "গান দেখতে স্টোরেজ পারমিশন দিন", Toast.LENGTH_LONG).show();
            if (!micOk) Toast.makeText(this, "রেকর্ড করতে মাইক্রোফোন পারমিশন দিন", Toast.LENGTH_LONG).show();
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
}

