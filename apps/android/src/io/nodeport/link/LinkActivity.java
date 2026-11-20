package io.nodeport.link;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.*;
import android.security.keystore.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.net.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;

/** Foreground-only native pairing surface. Nothing listens until the owner taps Host. */
public final class LinkActivity extends Activity {
  private LinearLayout body;
  private TextView status;
  private EditText keyField;
  private LanDiscovery.Peer selectedPeer;
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
    keyField = null;
    selectedPeer = null;
    body.removeAllViews();
    text("NODEPORT LINK 0.4   /   PRIVATE LAN", 13);
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
    page("Nearby devices");
    if (!note.isEmpty())
      text(note, 16);
    text("Use the same private Wi-Fi or Ethernet LAN. A key requests access; the target owner must "
         + "approve.",
        16);
    button("Find nearby hosts (LAN)", () -> chooseInterface(true));
    button("Host this phone · volume only", () -> chooseInterface(false));
    text("Connect to a host", 19);
    connectionForm(null);
  }
  private void connectionForm(LanDiscovery.Peer peer) {
    selectedPeer = peer;
    button("Scan host QR with camera",
        () -> startActivityForResult(new Intent(this, ScanActivity.class), 44));
    keyField = new EditText(this);
    keyField.setSingleLine();
    keyField.setSaveEnabled(false);
    keyField.setHint("Scan or paste the full NP1 key");
    keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    body.addView(keyField);
    button("Request volume only", () -> requestAccess("VOLUME"));
    button("View Windows screen (no input)", () -> requestAccess("SCREEN"));
    button("View Windows screen + tap to click", () -> requestAccess("SCREEN_CLICK"));
  }
  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != 44 || resultCode != RESULT_OK || data == null || keyField == null)
      return;
    String key = data.getStringExtra("key");
    if (!PairLink.validInvite(key) || (selectedPeer != null && !selectedPeer.matches(key))) {
      keyField.setError("QR does not match this invitation. Rescan the current host.");
      return;
    }
    keyField.setText(key);
    Toast.makeText(this, "QR read. Choose the access you want to request.", Toast.LENGTH_LONG)
        .show();
  }
  private void requestAccess(String scope) {
    String key = PairLink.clean(keyField.getText().toString());
    if (!PairLink.validInvite(key)) {
      keyField.setError(
          "Scan or paste all 55 characters including NP1-. Upper and lower case matter.");
      return;
    }
    if (selectedPeer != null && !selectedPeer.matches(key)) {
      keyField.setError(
          "Key does not match this host and port. Refresh the scan or enter the key directly.");
      return;
    }
    join(key, scope);
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
  private void chooseInterface(boolean scan) {
    try {
      List<AndroidLan> ips = AndroidLan.all(this);
      List<String> names = new ArrayList<>();
      for (AndroidLan lan : ips)
        names.add(lan.label + " · " + lan.ip.getHostAddress() + "/" + lan.prefix);
      if (ips.isEmpty()) {
        home("No private IPv4 address. Connect to Wi-Fi first.");
        return;
      }
      if (ips.size() == 1) {
        if (scan)
          scan(ips.get(0));
        else
          host(ips.get(0));
        return;
      }
      new AlertDialog.Builder(this)
          .setTitle("Choose a private LAN")
          .setItems(names.toArray(new String[0]),
              (d, i) -> {
                if (scan)
                  scan(ips.get(i));
                else
                  host(ips.get(i));
              })
          .show();
    } catch (Exception e) {
      home("Could not read network interfaces.");
    }
  }
  private void scan(AndroidLan lan) {
    InetAddress addr = lan.ip;
    PairLink.Lease l = begin("Scanning this LAN");
    status.setText("Listening for 6 seconds on " + lan.label + " · " + addr.getHostAddress() + "/"
        + lan.prefix + ". Keep the target on its invitation screen.");
    new Thread(() -> {
      WifiManager.MulticastLock lock = null;
      try {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
          lock = wifi.createMulticastLock("nodeport-scan");
          lock.acquire();
        }
        List<LanDiscovery.Peer> peers = LanDiscovery.scan(l, addr, lan.prefix, lan::bind);
        update(l, () -> {
          stop();
          page("Nearby devices");
          text(peers.isEmpty()
                  ? "No hosts found. Open Receive a request on the other device and keep it on the "
                      + "same Wi-Fi. Guest networks may block discovery."
                  : "Seen on this LAN in the last scan. Select a host, then enter its private key.",
              16);
          for (LanDiscovery.Peer peer : peers)
            button(peer.name + " · " + peer.target.getAddress().getHostAddress() + ":"
                    + peer.target.getPort(),
                () -> selected(peer));
          button("Scan again", () -> scan(lan));
          button("Back / enter key directly", () -> home(""));
        });
      } catch (Exception e) {
        update(l,
            () -> finishSession("Discovery failed: " + e.getMessage() + ". Try the direct key."));
      } finally {
        if (lock != null && lock.isHeld())
          lock.release();
      }
    }, "Nodeport nearby scan").start();
  }
  private void selected(LanDiscovery.Peer peer) {
    page(peer.name);
    text("Selected " + peer.target.getAddress().getHostAddress() + ":" + peer.target.getPort()
            + ". Ask the owner for this host’s NP1 key.",
        16);
    connectionForm(peer);
    button("Back", () -> home(""));
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
    InviteQr.Image raster = new InviteQr.Image(key);
    Bitmap bitmap =
        Bitmap.createBitmap(raster.pixels, raster.side, raster.side, Bitmap.Config.ARGB_8888);
    bitmap.setDensity(Bitmap.DENSITY_NONE);
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setBackgroundColor(Color.WHITE);
    if (Build.VERSION.SDK_INT >= 29)
      image.setForceDarkAllowed(false);
    image.setImageBitmap(bitmap);
    image.setContentDescription("Private one-time invitation QR");
    body.addView(image, new LinearLayout.LayoutParams(-1, dp(240)));
  }
  private void host(AndroidLan lan) {
    InetAddress addr = lan.ip;
    PairLink.Lease l = begin("Receive a request");
    worker(l, () -> {
      KeyStore ks = identity();
      KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      km.init(ks, null);
      SSLContext tls = SSLContext.getInstance("TLS");
      tls.init(km.getKeyManagers(), null, PairLink.RANDOM);
      PairLink.host(l, addr, tls, ks.getCertificate(ALIAS).getEncoded(), new PairLink.Host() {
        private final AtomicBoolean available = new AtomicBoolean(true);
        public void invite(String key) {
          byte[] payload = Base64.getUrlDecoder().decode(key.substring(4));
          int port = (payload[4] & 255) * 256 + (payload[5] & 255);
          new Thread(() -> {
            try {
              LanDiscovery.announce(l, available, addr, LanDiscovery.broadcast(addr, lan.prefix),
                  port, Build.MANUFACTURER + " " + Build.MODEL, lan::bind);
            } catch (Exception e) {
              update(l, () -> text("Discovery unavailable. Use the private key instead.", 14));
            }
          }, "Nodeport beacon").start();
          update(l, () -> {
            progress("Your one-time device key",
                "Valid for 2 minutes. Share privately with the operator. Keep this app open. You "
                    + "will be asked before access is granted. Switching apps stops this "
                    + "invitation.");
            showCode(key);
            text("On the controller: tap Scan host QR with camera. Scan the live display, not a "
                 + "screenshot (screenshots are protected). Host: "
                    + addr.getHostAddress() + ":" + port,
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
          available.set(false);
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
  private void join(String key, String scope) {
    PairLink.Lease l = begin("Requesting access");
    DesktopViewer viewer = new DesktopViewer(this, l, scope.equals("SCREEN_CLICK"));
    worker(l,
        ()
            -> PairLink.join(l, key, scope,
                (sock, peer)
                    -> AndroidLan.route(this, sock, peer),
                new PairLink.ScreenController() {
                  public void frame(byte[] image) throws Exception {
                    viewer.frame(image);
                  }
                  public String takeClick() {
                    return viewer.pending.getAndSet(null);
                  }
                  public void pending(String code) {
                    update(l,
                        ()
                            -> progress("Waiting for approval",
                                "Tell the owner your verification code: " + code
                                    + ". You have no access until they approve."));
                  }
                  public void active(PairLink.Lines io) {
                    update(l, () -> {
                      if (!scope.equals("VOLUME")) {
                        page("Windows primary display");
                        text(scope.equals("SCREEN_CLICK")
                                ? "Tap the image to left-click. No keyboard access."
                                : "View only · input is disabled.",
                            15);
                        button("Stop viewing", () -> finishSession("Disconnected."));
                        body.addView(viewer.create(), new LinearLayout.LayoutParams(-1, dp(340)));
                        return;
                      }
                      progress("Connected · volume control",
                          "The owner approved media volume up/down only.");
                      button("Volume up", () -> send(l, io, "UP"));
                      button("Volume down", () -> send(l, io, "DOWN"));
                    });
                  }
                  public void result(String r) {
                    update(l,
                        ()
                            -> status.setText(r.equals("OK") ? "Volume request accepted."
                                    : r.equals("WAIT")
                                    ? "Wait briefly before changing volume again."
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
