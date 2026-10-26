package dev.kenzolika.storytrack;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String BASE = BuildConfig.BASE_URL;
    private static final int REQ_FILE = 42;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Отладка WebView по USB — только в отладочных сборках
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0E0E12"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " StoryTrackApp/" + BuildConfig.VERSION_NAME);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());

        loadFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadFromIntent(intent);
    }

    /** Собираем адрес: если пришёл «Поделиться» — передаём текст в страницу через ?text= */
    private void loadFromIntent(Intent intent) {
        String url = BASE + "/?app=1";
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            StringBuilder all = new StringBuilder();
            if (subject != null) all.append(subject).append(' ');
            if (text != null) all.append(text);
            if (all.length() > 0) url += "&text=" + enc(all.toString().trim());
        } else if (web.getUrl() != null) {
            return; // просто вернулись в приложение — ничего не перезагружаем
        }
        web.loadUrl(url);
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    // ---------- навигация и ошибки ----------
    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u.getHost() != null && Uri.parse(BASE).getHost().equals(u.getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) { }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (!req.isForMainFrame()) return;
            String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                    + "<body style='margin:0;background:#0e0e12;color:#f3f3f7;font-family:sans-serif;"
                    + "display:flex;align-items:center;justify-content:center;height:100vh;text-align:center;padding:24px;box-sizing:border-box'>"
                    + "<div><div style='font-size:48px'>📡</div>"
                    + "<h2 style='margin:12px 0 8px'>Нет связи с сервером</h2>"
                    + "<p style='color:#9a9aa8;line-height:1.5'>Проверь интернет и VPN —<br>Cloudflare без него может не открываться.</p>"
                    + "<button onclick=\"location.href='" + BASE + "/?app=1'\" style='margin-top:16px;padding:14px 22px;border:0;"
                    + "border-radius:12px;font-size:16px;font-weight:700;background:linear-gradient(90deg,#ff4f7b,#ffb03b);color:#1a0710'>"
                    + "Попробовать ещё раз</button></div></body>";
            view.loadDataWithBaseURL(BASE, html, "text/html", "utf-8", null);
        }
    }

    // ---------- выбор файла для «Своя обложка» ----------
    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(Intent.createChooser(i, "Обложка"), REQ_FILE);
                return true;
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
        }
    }

    // ---------- мост для страницы: window.Android ----------
    private class Bridge {
        @JavascriptInterface
        public boolean isApp() {
            return true;
        }

        @JavascriptInterface
        public void shareImage(String base64, String name) {
            try {
                byte[] bytes = decode(base64);
                File dir = new File(getCacheDir(), "share");
                if (!dir.exists()) dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                File out = new File(dir, safeName(name));
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(bytes);
                }
                Uri uri = Uri.parse("content://" + ShareProvider.AUTHORITY + "/" + Uri.encode(out.getName()));

                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("image/jpeg");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.setClipData(ClipData.newRawUri("", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                Intent chooser = Intent.createChooser(send, getString(R.string.share_chooser));
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(chooser));
            } catch (Exception e) {
                toast("Не получилось поделиться: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void saveImage(String base64, String name) {
            try {
                byte[] bytes = decode(base64);
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, safeName(name));
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Сторис-трек");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("галерея недоступна");
                try (OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null) throw new IllegalStateException("не открылся файл");
                    os.write(bytes);
                }
                v.clear();
                v.put(MediaStore.Images.Media.IS_PENDING, 0);
                cr.update(uri, v, null, null);
                toast("Сохранено в галерею → Сторис-трек");
            } catch (Exception e) {
                toast("Не получилось сохранить: " + e.getMessage());
            }
        }
    }

    private static byte[] decode(String base64) {
        int comma = base64.indexOf(',');
        if (base64.startsWith("data:") && comma > 0) base64 = base64.substring(comma + 1);
        return Base64.decode(base64, Base64.DEFAULT);
    }

    private static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]+", "").trim();
        if (n.isEmpty()) n = "story";
        if (!n.toLowerCase().endsWith(".jpg")) n += ".jpg";
        return n;
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
