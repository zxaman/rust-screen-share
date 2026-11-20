package io.nodeport.link;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.media.AudioManager;
import android.os.*;
import android.security.keystore.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import io.nayuki.qrcodegen.QrCode;
import java.io.IOException;
import java.net.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Foreground-only native pairing surface. Nothing listens until the owner taps Host. */
public final class LinkActivity extends Activity {
  private LinearLayout body;
  private TextView status;
  private AlertDialog request;
  private PairLink.Lease lease;
  private final ExecutorService writes = Executors.newSingleThreadExecutor();
  private static final String ALIAS = "nodeport-link-tls";
  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }
  private TextView text(String s, int size) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(Color.rgb(28, 50, 43));
    t.setPadding(0, dp(6), 0, dp(6));
    body.addView(t);
    return t;
  }
  private Button button(String s, Runnable fn) {
    Button b = new Button(this);
    b.setAllCaps(false);
    b.setText(s);
    body.addView(b);
    b.setOnClickListener(v -> fn.run());
    return b;
  }
  private void page(String title) {
    body.removeAllViews();
    text("NODEPORT   /   PRIVATE LAN", 13);
    text(title, 27);
  }
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    ScrollView scroll = new ScrollView(this);
    scroll.setFitsSystemWindows(true);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20), dp(28), dp(20), dp(20));
    scroll.addView(body);
    setContentView(scroll);
    home("");
  }
  private void home(String note) {
    page("Device pairing");
    if (!note.isEmpty())
      text(note, 16);
    text("Connect both devices to the same private Wi-Fi or LAN. Only media volume can be changed. "
         + "No screen, file or input access.",
        16);
    button("Receive a request on this phone", () -> chooseInterface());
    text("Control another phone or Windows PC", 19);
    EditText key = new EditText(this);
    key.setSingleLine();
    key.setHint("Paste the owner’s NP1 device key");
    key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    body.addView(key);
    button("Request volume control", () -> join(key.getText().toString().trim()));
  }
  private PairLink.Lease begin(String title) {
    stop();
    PairLink.Lease l = new PairLink.Lease();
    lease = l;
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    progress(title, "Connecting…");
    return l;
  }
  private void progress(String title, String msg) {
    page(title);
    status = text(msg, 16);
    button("Stop / disconnect", () -> finishSession("Disconnected."));
  }
  private void stop() {
    if (lease != null) {
      // Revoke before returning to the UI. TLS shutdown may perform I/O.
      PairLink.Lease ending = lease;
      ending.closed = true;
      lease = null;
      new Thread(ending::close, "Nodeport disconnect").start();
    }
    if (request != null) {
      request.dismiss();
      request = null;
    }
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }
  private void finishSession(String message) {
    stop();
    home(message);
  }
  @Override
  public void onStop() {
    super.onStop();
    if (lease != null)
      finishSession("Sharing stopped because Nodeport left the foreground.");
  }
  @Override
  public void onDestroy() {
    stop();
    writes.shutdownNow();
    super.onDestroy();
  }
  private void update(PairLink.Lease l, Runnable action) {
    runOnUiThread(() -> {
      if (lease == l && !l.closed)
        action.run();
    });
  }
  private interface Job {
    void run() throws Exception;
  }
  private void worker(PairLink.Lease l, Job job) {
    new Thread(() -> {
      String result = "Session ended.";
      try {
        job.run();
      } catch (Exception e) {
        result =
            "Stopped: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      } finally {
        l.close();
      }
      final String msg = result;
      runOnUiThread(() -> {
        if (lease == l)
          finishSession(msg);
      });
    }, "Nodeport link").start();
  }
  private void chooseInterface() {
    try {
      List<InetAddress> ips = new ArrayList<>();
      List<String> names = new ArrayList<>();
      for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces()))
        if (n.isUp())
          for (InetAddress a : Collections.list(n.getInetAddresses()))
            if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
              ips.add(a);
              names.add(n.getName() + " · " + a.getHostAddress());
            }
      if (ips.isEmpty()) {
        home("No private IPv4 address. Connect to Wi-Fi first.");
        return;
      }
      if (ips.size() == 1) {
        host(ips.get(0));
        return;
      }
      new AlertDialog.Builder(this)
          .setTitle("Choose a private LAN")
          .setItems(names.toArray(new String[0]), (d, i) -> host(ips.get(i)))
          .show();
    } catch (Exception e) {
      home("Could not read network interfaces.");
    }
  }
  private static synchronized KeyStore identity() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias(ALIAS)) {
      KeyPairGenerator g =
          KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
      g.initialize(new KeyGenParameterSpec
              .Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
              .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
              .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                  KeyProperties.DIGEST_SHA512)
              .build());
      g.generateKeyPair();
    }
    return ks;
  }
  private void showCode(String key) {
    QrCode qr = QrCode.encodeText(key, QrCode.Ecc.MEDIUM);
    View image = new View(this) {
      final Paint ink = new Paint();
      @Override
      protected void onDraw(Canvas c) {
        int cell = Math.min(getWidth(), getHeight()) / (qr.size + 8);
        int x = (getWidth() - cell * qr.size) / 2, y = (getHeight() - cell * qr.size) / 2;
        c.drawColor(Color.WHITE);
        ink.setColor(Color.BLACK);
        for (int row = 0; row < qr.size; row++)
          for (int col = 0; col < qr.size; col++)
            if (qr.getModule(col, row))
              c.drawRect(
                  x + cell * col, y + cell * row, x + cell * (col + 1), y + cell * (row + 1), ink);
      }
    };
    image.setContentDescription("QR code containing this temporary device key");
    body.addView(image, new LinearLayout.LayoutParams(-1, dp(200)));
  }
  private void host(InetAddress addr) {
    PairLink.Lease l = begin("Receive a request");
    worker(l, () -> {
      KeyStore ks = identity();
      KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      km.init(ks, null);
      SSLContext tls = SSLContext.getInstance("TLS");
      tls.init(km.getKeyManagers(), null, PairLink.RANDOM);
      PairLink.host(l, addr, tls, ks.getCertificate(ALIAS).getEncoded(), new PairLink.Host() {
        public void invite(String key) {
          update(l, () -> {
            progress("Your one-time device key",
                "Valid for 2 minutes. Share privately with the operator. Keep this app open. You "
                + "will be asked before access is granted.");
            showCode(key);
            text("On the other phone: scan with its camera, copy the text, then paste it into "
                 + "Nodeport Link.",
                14);
            TextView value = text(key, 18);
            value.setTextIsSelectable(true);
            button("Copy device key", () -> {
              android.content.ClipboardManager cb =
                  (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
              ClipData clip = ClipData.newPlainText("One-time Nodeport key", key);
              PersistableBundle flags = new PersistableBundle();
              flags.putBoolean("android.content.extra.IS_SENSITIVE", true);
              clip.getDescription().setExtras(flags);
              cb.setPrimaryClip(clip);
            });
          });
        }
        public boolean approve(String peer, String code, long deadline) throws Exception {
          CompletableFuture<Boolean> decision = new CompletableFuture<>();
          update(l, () -> {
            progress("Incoming pair request", "Waiting for your decision.");
            request =
                new AlertDialog.Builder(LinkActivity.this)
                    .setTitle("Allow this device to pair?")
                    .setMessage(peer + " wants to change this phone’s media volume. Compare code "
                        + code + " with the operator. Deny if it does not match.")
                    .setPositiveButton("Allow volume control", (d, w) -> decision.complete(true))
                    .setNegativeButton("Deny", (d, w) -> decision.complete(false))
                    .setOnCancelListener(d -> decision.complete(false))
                    .show();
            request.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
          });
          while (!l.closed && PairLink.now() < deadline) {
            try {
              return decision.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
            }
          }
          return false;
        }
        public void active(String peer) {
          update(l,
              ()
                  -> progress("Volume control is active",
                      peer + " can change media volume only. Stop to revoke access immediately."));
        }
        public boolean volume(boolean up) throws Exception {
          CompletableFuture<Boolean> done = new CompletableFuture<>();
          runOnUiThread(() -> {
            if (lease != l || l.closed) {
              done.complete(false);
              return;
            }
            try {
              AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
              if (audio.isVolumeFixed()) {
                done.complete(false);
                return;
              }
              audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                  up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                  AudioManager.FLAG_SHOW_UI);
              done.complete(true);
            } catch (RuntimeException e) {
              done.complete(false);
            }
          });
          return done.get(2, TimeUnit.SECONDS);
        }
      });
    });
  }
  private void join(String key) {
    PairLink.Lease l = begin("Requesting access");
    worker(l, () -> PairLink.join(l, key, new PairLink.Controller() {
      public void pending(String code) {
        update(l,
            ()
                -> progress("Waiting for approval",
                    "Tell the owner your verification code: " + code
                        + ". You have no access until they approve."));
      }
      public void active(PairLink.Lines io) {
        update(l, () -> {
          progress("Connected · volume control", "The owner approved media volume up/down only.");
          button("Volume up", () -> send(l, io, "UP"));
          button("Volume down", () -> send(l, io, "DOWN"));
        });
      }
      public void result(String r) {
        update(l,
            ()
                -> status.setText(r.equals("OK") ? "Volume request accepted."
                        : r.equals("WAIT")       ? "Wait briefly before changing volume again."
                                                 : "Device could not change volume."));
      }
    }));
  }
  private void send(PairLink.Lease l, PairLink.Lines io, String command) {
    writes.execute(() -> {
      if (!l.closed)
        try {
          io.send(command);
        } catch (IOException e) {
          l.close();
        }
    });
  }
}
