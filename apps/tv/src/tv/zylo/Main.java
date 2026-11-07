package tv.zylo;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Zylo for Android TV: Zylo's own web app, full screen, driven by the remote.
 * First launch (or holding Back) asks for the server address, e.g. http://192.168.1.20:3000.
 */
public class Main extends Activity {
  private SharedPreferences prefs;
  private FrameLayout root;
  private WebView web;
  private View fullscreen; // a video playing full screen
  private WebChromeClient.CustomViewCallback exitFullscreen;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = getSharedPreferences("zylo", MODE_PRIVATE);
    root = new FrameLayout(this);
    root.setBackgroundColor(Color.rgb(11, 11, 13));
    setContentView(root);
    String server = prefs.getString("server", null);
    if (server == null) askForServer(null); else open(server);
  }

  private WebChromeClient chrome;

  private void open(final String server) {
    root.removeAllViews();
    web = new WebView(this);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true); // the active profile lives in localStorage
    s.setMediaPlaybackRequiresUserGesture(false); // autoplay the next source / episode
    s.setUserAgentString(s.getUserAgentString() + " ZyloTV/0.7");
    CookieManager.getInstance().setAcceptCookie(true);
    web.setBackgroundColor(Color.rgb(11, 11, 13));
    web.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView v, String url) { v.evaluateJavascript(remoteJs(), null); }
      @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
        if (r.isForMainFrame()) askForServer("Couldn't reach " + server + " (" + e.getDescription() + "). Check the address and that Zylo is running.");
      }
    });
    web.setWebChromeClient(chrome = new WebChromeClient() {
      @Override public void onShowCustomView(View v, CustomViewCallback cb) {
        fullscreen = v;
        exitFullscreen = cb;
        root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setVisibility(View.GONE);
      }
      @Override public void onHideCustomView() {
        if (fullscreen == null) return;
        root.removeView(fullscreen);
        fullscreen = null;
        web.setVisibility(View.VISIBLE);
        exitFullscreen.onCustomViewHidden();
      }
    });
    root.addView(web);
    web.loadUrl(server);
    web.requestFocus();
  }

  private void askForServer(String problem) {
    if (web != null) { web.destroy(); web = null; }
    root.removeAllViews();
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setGravity(Gravity.CENTER);
    box.setPadding(dp(64), dp(48), dp(64), dp(48));

    TextView title = text("Zylo", 34, Color.WHITE);
    TextView hint = text(problem != null ? problem : "Enter your Zylo server's address, as you'd type it in a browser on another device.", 16, problem != null ? Color.rgb(239, 122, 112) : Color.rgb(163, 163, 173));
    hint.setPadding(0, dp(12), 0, dp(24));

    final EditText url = new EditText(this);
    url.setText(prefs.getString("server", "http://192.168.1.:3000"));
    url.setSelection(url.getText().length());
    url.setSingleLine(true);
    url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    url.setImeOptions(EditorInfo.IME_ACTION_GO);
    url.setTextColor(Color.WHITE);
    url.setTextSize(20);
    url.setMinWidth(dp(520));

    Button go = new Button(this);
    go.setText("Open Zylo");
    final View.OnClickListener connect = new View.OnClickListener() {
      @Override public void onClick(View v) {
        String s = url.getText().toString().trim();
        if (!s.matches("(?i)^https?://.*")) s = "http://" + s;
        prefs.edit().putString("server", s).apply();
        open(s);
      }
    };
    go.setOnClickListener(connect);
    url.setOnEditorActionListener(new TextView.OnEditorActionListener() {
      @Override public boolean onEditorAction(TextView v, int action, KeyEvent e) { connect.onClick(v); return true; }
    });

    TextView tip = text("Tip: hold Back at any time to change the server.", 14, Color.rgb(116, 116, 126));
    tip.setPadding(0, dp(24), 0, 0);

    box.addView(title);
    box.addView(hint);
    box.addView(url);
    box.addView(go);
    box.addView(tip);
    root.addView(box);
    url.requestFocus();
  }

  @Override public boolean onKeyLongPress(int keyCode, KeyEvent e) {
    if (keyCode == KeyEvent.KEYCODE_BACK) { askForServer(null); return true; }
    return super.onKeyLongPress(keyCode, e);
  }

  @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
    if (keyCode == KeyEvent.KEYCODE_BACK) { e.startTracking(); return true; } // so a long press can be told apart
    return super.onKeyDown(keyCode, e);
  }

  @Override public boolean onKeyUp(int keyCode, KeyEvent e) {
    if (keyCode == KeyEvent.KEYCODE_BACK && e.isTracking() && !e.isCanceled()) {
      if (fullscreen != null) { chrome.onHideCustomView(); return true; } // leave full-screen video first
      if (web != null && web.canGoBack()) { web.goBack(); return true; }
      finish();
      return true;
    }
    return super.onKeyUp(keyCode, e);
  }

  @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); CookieManager.getInstance().flush(); }
  @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }

  private String remoteJs;
  /** assets/remote.js: D-pad focus movement, which Android's WebView doesn't do by itself. */
  private String remoteJs() {
    if (remoteJs != null) return remoteJs;
    try (java.io.InputStream in = getAssets().open("remote.js")) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
      return remoteJs = out.toString("UTF-8");
    } catch (java.io.IOException e) {
      return "";
    }
  }

  private TextView text(String s, int sp, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(sp);
    t.setTextColor(color);
    t.setGravity(Gravity.CENTER);
    return t;
  }

  private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
