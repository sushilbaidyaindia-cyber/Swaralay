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

          + "function __addPickerButtons(){"
          + "  var ids=['cutFile','boostFile','pitchFile','fxFile','fileInput'];"
          + "  ids.forEach(function(id){"
          + "    var el=document.getElementById(id);"
          + "    if(!el||el.dataset.nativeBtn)return;"
          + "    el.dataset.nativeBtn='1';"
          + "    var b=document.createElement('button');"
          + "    b.type='button';"
          + "    b.textContent='📱 ফোনের সব গান থেকে বেছে নিন';"
          + "    b.setAttribute('aria-label','ফোনের সব গান থেকে বেছে নিন');"
          + "    b.style.cssText='width:100%;margin-top:8px;padding:12px;background:#7c4dff;color:#fff;border:none;border-radius:8px;font-weight:bold;';"
          + "    b.onclick=function(e){e.preventDefault();window.__openNativeSongPicker(id);};"
          + "    el.parentNode.insertBefore(b,el.nextSibling);"
          + "  });"
          + "  var addBtnObserver=function(){"
          + "    document.querySelectorAll('.track-row .file-input').forEach(function(inp){"
          + "      if(inp.dataset.nativeBtn)return;"
          + "      inp.dataset.nativeBtn='1';"
          + "      var b=document.createElement('button');"
          + "      b.type='button';b.textContent='📱 ফোন থেকে';"
          + "      b.setAttribute('aria-label','ফোনের গান থেকে বেছে নিন');"
          + "      b.style.cssText='margin-top:6px;padding:8px;background:#7c4dff;color:#fff;border:none;border-radius:6px;width:100%;';"
          + "      b.onclick=function(ev){"
          + "        ev.preventDefault();"
          + "        if(!inp.id){inp.id='dynFile_'+Math.random().toString(36).slice(2);}"
          + "        window.__openNativeSongPicker(inp.id);"
          + "      };"
          + "      inp.parentNode.appendChild(b);"
          + "    });"
          + "  };"
          + "  addBtnObserver();"
          + "  setInterval(addBtnObserver,1500);"
          + "}"
          + "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',__addPickerButtons);"
          + "else __addPickerButtons();"
          + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void checkPermissionAndScan() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanAndPush();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{permission}, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanAndPush();
            } else {
                Toast.makeText(this, "গান দেখতে পারমিশন দিন", Toast.LENGTH_LONG).show();
            }
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
                MediaStore.Audio.Media.ARTIST
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";
        String sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";
        try (Cursor c = getContentResolver().query(collection, projection, selection, null, sort)) {
            if (c == null) return list;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
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
