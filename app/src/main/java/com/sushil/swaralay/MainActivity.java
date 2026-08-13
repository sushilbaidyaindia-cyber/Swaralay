package com.sushil.swaralay;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final int FILE_CHOOSER_REQUEST_CODE = 456;
    private ValueCallback<Uri[]> mFilePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkPermissionsAndLoadMusic();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (ActivityNotFoundException e) {
                    mFilePathCallback = null;
                    Toast.makeText(MainActivity.this, "Cannot open file chooser", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mFilePathCallback == null) return;
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            mFilePathCallback.onReceiveValue(result);
            mFilePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void checkPermissionsAndLoadMusic() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ?
                Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{permission}, PERMISSION_REQUEST_CODE);
        } else {
            sendMusicListToWebView();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            sendMusicListToWebView();
        } else {
            Toast.makeText(this, "Permission Denied!", Toast.LENGTH_LONG).show();
        }
    }

    private void sendMusicListToWebView() {
        JSONArray musicList = new JSONArray();
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST
        };

        Uri audioUri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        try (Cursor cursor = getContentResolver().query(audioUri, projection, null, null, null)) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String title = cursor.getString(titleColumn);
                    String artist = cursor.getString(artistColumn);
                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);

                    JSONObject song = new JSONObject();
                    song.put("title", title != null ? title : "Unknown Title");
                    song.put("artist", artist != null ? artist : "Unknown Artist");
                    song.put("path", contentUri.toString());
                    musicList.put(song);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        String jsonString = musicList.toString();
        String base64Json = Base64.encodeToString(jsonString.getBytes(), Base64.NO_WRAP);

        String jsCommand = "javascript:(function() { " +
            "  window.populateAllAudioLists = function(data) { " +
            "    var container = document.getElementById('music-list') || document.body; " +
            "    var html = '<h3 style=\"color:black; padding:10px;\">All Phones Songs</h3><ul style=\"list-style:none; padding:0;\">'; " +
            "    data.forEach(function(song) { " +
            "      html += '<li style=\"padding:15px; border-bottom:1px solid #ccc;\"><a href=\"#\" style=\"color:#4CAF50; text-decoration:none; font-size:18px; display:block;\" onclick=\"playAudio(\\'' + song.path + '\\')\">🎵 ' + song.title + '<br><small style=\"color:#666;\">' + song.artist + '</small></a></li>'; " +
            "    }); " +
            "    html += '</ul>'; " +
            "    var div = document.createElement('div'); " +
            "    div.innerHTML = html; " +
            "    container.appendChild(div); " +
            "  }; " +
            "  window.playAudio = function(uri) { " +
            "    var audioPlayer = document.getElementById('audio-player') || document.querySelector('audio'); " +
            "    if(audioPlayer) { " +
            "      audioPlayer.src = uri; " +
            "      audioPlayer.play(); " +
            "    } " +
            "  }; " +
            "  window.populateAllAudioLists(JSON.parse(atob('" + base64Json + "'))); " +
            "})();";

        webView.post(() -> webView.evaluateJavascript(jsCommand, null));
    }
}
