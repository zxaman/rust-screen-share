package io.nodeport.link;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

/** Exercises the actual Android transport on a JVM, with a fake volume adapter. */
public final class ProtocolSmoke {
  static void require(boolean yes) {
    if (!yes)
      throw new AssertionError();
  }
  static void control(String key) throws Exception {
    PairLink.Lease lease = new PairLink.Lease();
    AtomicInteger acknowledged = new AtomicInteger();
    try {
      PairLink.join(lease, key, new PairLink.Controller() {
        PairLink.Lines channel;
        public void pending(String code) {
          require(code.matches("[0-9]{6}"));
        }
        public void active(PairLink.Lines io) {
          channel = io;
          send("UP");
        }
        void send(String cmd) {
          try {
            channel.send(cmd);
          } catch (IOException e) {
            throw new AssertionError(e);
          }
        }
        public void result(String message) {
          require(message.equals("OK"));
          acknowledged.incrementAndGet();
          send("STOP");
        }
      });
    } catch (EOFException expected) {
      if (acknowledged.get() == 0)
        throw expected;
    } finally {
      lease.close();
    }
    require(acknowledged.get() == 1);
  }
  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      // The host advertises on loopback from .2; the logical controller is .1.
      String key = new BufferedReader(new InputStreamReader(System.in)).readLine();
      try (DatagramSocket sock = new DatagramSocket(LanDiscovery.PORT);
          PairLink.Lease scan = new PairLink.Lease()) {
        List<LanDiscovery.Peer> found =
            LanDiscovery.collect(scan, sock, InetAddress.getByName("127.0.0.1"), 8, 1800);
        require(found.size() == 1 && found.get(0).matches(key));
      }
      control(key);
      return;
    }
    if (args[0].equals("announce")) {
      try (PairLink.Lease scan = new PairLink.Lease()) {
        LanDiscovery.announce(scan, new AtomicBoolean(true), InetAddress.getByName("127.0.0.2"),
            InetAddress.getByName("127.255.255.255"), 4321, "Android fixture");
      }
      return;
    }
    final InetAddress address = InetAddress.getByName("127.0.0.1");
    try (DatagramSocket sock = new DatagramSocket(0, address);
        PairLink.Lease lease = new PairLink.Lease()) {
      require(LanDiscovery.collect(lease, sock, address, 8, 40).isEmpty());
    }
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream f = new FileInputStream(args[0])) {
      ks.load(f, "fixturepass".toCharArray());
    }
    KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(ks, "fixturepass".toCharArray());
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(km.getKeyManagers(), null, null);
    for (boolean allow : new boolean[] {false, true}) {
      PairLink.Lease lease = new PairLink.Lease();
      CompletableFuture<String> invite = new CompletableFuture<>();
      AtomicInteger prompts = new AtomicInteger(), changes = new AtomicInteger();
      FutureTask<Void> host = new FutureTask<>(() -> {
        try {
          PairLink.host(
              lease, address, tls, ks.getCertificate("host").getEncoded(), new PairLink.Host() {
                public void invite(String key) {
                  invite.complete(key);
                }
                public boolean approve(String peer, String code, long deadline) {
                  require(code.matches("[0-9]{6}") && deadline > PairLink.now());
                  prompts.incrementAndGet();
                  return allow;
                }
                public void active(String peer) {
                  require(allow && prompts.get() == 1);
                }
                public boolean volume(boolean up) {
                  require(allow && up);
                  changes.incrementAndGet();
                  return true;
                }
              });
          return null;
        } finally {
          lease.close();
        }
      });
      new Thread(host).start();
      String key = invite.get(5, TimeUnit.SECONDS);
      for (int at :
          new int[] {6, 22}) { // corrupt the pin, then the secret; neither may show a prompt
        byte[] bytes = Base64.getUrlDecoder().decode(key.substring(4));
        bytes[at] ^= 1;
        try {
          control("NP1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
          throw new AssertionError();
        } catch (IOException expected) {
        }
        require(prompts.get() == 0 && changes.get() == 0);
      }
      if (allow)
        control(key);
      else
        try {
          control(key);
          throw new AssertionError();
        } catch (IOException expected) {
          require("Pairing denied".equals(expected.getMessage()));
        }
      host.get(5, TimeUnit.SECONDS);
      require(changes.get() == (allow ? 1 : 0));
      require(prompts.get() == 1);
      try {
        control(key);
        throw new AssertionError("consumed key reused");
      } catch (IOException expected) { require(expected.getCause() instanceof ConnectException);
      }
    }
    System.out.println(
        "Android protocol: pin, secret, deny, allow, scoped action and replay checks passed");
  }
}
