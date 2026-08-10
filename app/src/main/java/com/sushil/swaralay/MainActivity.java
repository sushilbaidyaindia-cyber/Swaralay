package com.sushil.swaralay;

import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private WebView myWebView;
    private static final int PERMISSION_REQUEST_CODE = 123;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // অ্যাপের লেআউট সেট করা (যেটিতে WebView আছে)
        setContentView(R.layout.activity_main);

        // R.id.webview হলো activity_main.xml ফাইলের WebView এর ID
        myWebView = findViewById(R.id.webview);
        WebSettings webSettings = myWebView.getSettings();
        // জাভাস্ক্রিপ্ট অন করা (HTML পেজের জন্য দরকার)
        webSettings.setJavaScriptEnabled(true);
        // ফাইলে অ্যাক্সেস দেওয়া
        webSettings.setAllowFileAccess(true);
        // IndexedDB প্লেলিস্টের জন্য ডাটাবেস অন করা
        webSettings.setDomStorageEnabled(true);

        // জাভা এবং জাভাস্ক্রিপ্টের মধ্যে একটা ব্রিজ বানানো, যাতে জাভা কোড HTML-এ ডাটা পাঠাতে পারে
        myWebView.addJavascriptInterface(new AudioScannerBridge(), "AndroidBridge");

        myWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // HTML পেজ পুরোপুরি লোড হওয়ার পর ডাটা স্ক্যান এবং পাঠানোর কাজ শুরু হবে
                checkAndRequestPermissionsAndPushData();
            }
        });

        // assets ফোল্ডার থেকে index.html ফাইল লোড করা
        myWebView.loadUrl("file:///android_asset/index.html");
    }

    private void checkAndRequestPermissionsAndPushData() {
        String permission;
        // অ্যান্ড্রয়েড ভার্সন অনুযায়ী পারমিশন চেক করা
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_AUDIO; // অ্যান্ড্রয়েড ১৩ বা তার বেশি
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE; // পুরনো ভার্সন
        }

        // পারমিশন আগে থেকে দেওয়া আছে কি না চেক করা
        if (ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED) {
            // পারমিশন আছে, তাই অডিও ফাইল স্ক্যান করে HTML-এ পাঠিয়ে দেব
            // অ্যাপটিকে স্মুথ রাখার জন্য এই কাজ একটি ব্যাকগ্রাউন্ড থ্রেডে করা হবে
            new Thread(new Runnable() {
                @Override
                public void run() {
                    JSONArray allAudioFilesJson = scanAudioFiles();
                    pushAudioListToJavaScript(allAudioFilesJson);
                }
            }).start();
        } else {
            // পারমিশন নেই, ইউজারের কাছে পারমিশন চাওয়া হচ্ছে
            ActivityCompat.requestPermissions(this,
                    new String[]{permission}, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // ইউজার পারমিশন দিয়েছেন, তাই অডিও ফাইল স্ক্যান করে HTML-এ পাঠিয়ে দেব
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        JSONArray allAudioFilesJson = scanAudioFiles();
                        pushAudioListToJavaScript(allAudioFilesJson);
                    }
                }).start();
            } else {
                // ইউজার পারমিশন দেননি। ইউজারকে একটা বার্তা দেখানো হচ্ছে।
                final String deniedMsg = "মিডিয়া ফাইল পড়ার অনুমতি দেওয়া হয়নি। ফাইলগুলি স্বয়ংক্রিয়ভাবে স্ক্যান করা যাবে না।";
                myWebView.post(new Runnable() {
                    @Override
                    public void run() {
                        myWebView.evaluateJavascript("alert('" + deniedMsg + "');", null);
                    }
                });
            }
        }
    }

    // --- আসল মস্তিষ্ক: অটোমেটিক অডিও ফাইল স্ক্যান করার জাভা কোড ---
    private JSONArray scanAudioFiles() {
        JSONArray audioFilesJson = new JSONArray();
        ContentResolver contentResolver = getContentResolver();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        // আমরা ফাইলের নাম এবং ডাটা (ফাইলের রাস্তা) স্ক্যান করব
        String[] projection = {MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA};
        // আমরা শুধু সঙ্গীত ফাইলগুলি খুঁজব
        String selection = MediaStore.Audio.Media.IS_MUSIC + "!= 0";
        Cursor cursor = contentResolver.query(uri, projection, selection, null, null);

        if (cursor != null) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME));
                String path = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.DATA));

                // অডিও ফাইল সম্পর্কে তথ্য একটি JSON অবজেক্টে রাখা হচ্ছে
                JSONObject audioObj = new JSONObject();
                try {
                    audioObj.put("name", name);
                    audioObj.put("path", path); // ফাইলের আসল রাস্তা (Android Content URI নয়)
                    audioFilesJson.put(audioObj);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            cursor.close();
        }
        return audioFilesJson;
    }

    // JSON ফরম্যাটে স্ক্যান করা অডিও ফাইলের তালিকা HTML/জাভাস্ক্রিপ্ট পেজে পাঠানো
    private void pushAudioListToJavaScript(final JSONArray jsonArray) {
        myWebView.post(new Runnable() {
            @Override
            public void run() {
                // HTML পেজে 'populateAllAudioLists' নামের একটি জাভাস্ক্রিপ্ট ফাংশন কল করা হচ্ছে এবং স্ক্যান করা ডাটা পাঠানো হচ্ছে
                myWebView.evaluateJavascript("populateAllAudioLists('" + jsonArray.toString() + "');", null);
            }
        });
    }

    // জাভা এবং জাভাস্ক্রিপ্টের মধ্যে একটা ব্রিজ বানানোর জন্য একটি অভ্যন্তরীণ ক্লাস
    private class AudioScannerBridge {
        // এই ফাংশনটি HTML পেজ থেকে কল করা যাবে (যদিও আমরা এই উদাহরণের দরকার নেই)
        @JavascriptInterface
        public String getAudioFilesList() {
            // এই ফাংশনটিও HTML পেজ থেকে অডিও ফাইলের তালিকা পাওয়ার জন্য ব্যবহার করা যেতে পারে
            return ""; 
        }
    }
}
