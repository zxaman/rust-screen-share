package io.nodeport.link;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;

/** NP1 wire protocol. No discovery, persistent grants, or commands beyond volume. */
final class PairLink {
  static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
  static final long INVITE_MS = 120000, SESSION_MS = 600000;
  // Loopback is also supported for local protocol tests. The UI advertises LAN interfaces only.
  static boolean localIPv4(InetAddress ip) {
    return ip instanceof Inet4Address && (ip.isSiteLocalAddress() || ip.isLoopbackAddress());
  }
  static long now() {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
  }
  static void check(boolean ok, String error) throws IOException {
    if (!ok)
      throw new IOException(error);
  }
  static int left(long deadline, int cap) throws IOException {
    long ms = deadline - now();
    check(ms > 0, "Time limit reached");
    return (int) Math.min(ms, cap);
  }
  /** Cross-thread cancellation; late-created sockets are closed rather than leaked. */
  static final class Lease implements Closeable {
    private final Set<Closeable> resources = new HashSet<>();
    volatile boolean closed;
    synchronized<T extends Closeable> T track(T c) throws IOException {
      if (closed) {
        c.close();
        throw new IOException("Stopped");
      }
      resources.add(c);
      return c;
    }
    synchronized void discard(Closeable c) {
      resources.remove(c);
      try {
        c.close();
      } catch (IOException ignored) {
      }
    }
    public synchronized void close() {
      closed = true;
      for (Closeable c : resources) try {
          c.close();
        } catch (IOException ignored) {
        }
      resources.clear();
    }
  }
  static final class Lines {
    final Socket socket;
    final InputStream in;
    final OutputStream out;
    Lines(Socket s) throws IOException {
      socket = s;
      in = new BufferedInputStream(s.getInputStream());
      out = s.getOutputStream();
    }
    synchronized void send(String s) throws IOException {
      out.write((s + "\n").getBytes(StandardCharsets.US_ASCII));
      out.flush();
    }
    String read(long deadline) throws IOException {
      StringBuilder s = new StringBuilder();
      while (s.length() < 128) {
        socket.setSoTimeout(left(deadline, 60000));
        int b = in.read();
        if (b < 0)
          throw new EOFException("Peer disconnected");
        if (b == '\n')
          return s.toString();
        check(b >= 32 && b <= 126, "Invalid message");
        s.append((char) b);
      }
      throw new IOException("Message too long");
    }
  }
  interface Host {
    void invite(String key);
    boolean approve(String address, String code, long deadline) throws Exception;
    void active(String address);
    boolean volume(boolean up) throws Exception;
  }
  interface Controller {
    void pending(String code);
    void active(Lines channel);
    void result(String reply);
  }
  private static byte[] pin(byte[] der) throws GeneralSecurityException {
    return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(der), 16);
  }
  static String key(InetAddress ip, int port, byte[] pin, byte[] secret) {
    byte[] b = new byte[38];
    System.arraycopy(ip.getAddress(), 0, b, 0, 4);
    b[4] = (byte) (port >> 8);
    b[5] = (byte) port;
    System.arraycopy(pin, 0, b, 6, 16);
    System.arraycopy(secret, 0, b, 22, 16);
    return "NP1-" + ENCODE.encodeToString(b);
  }
  static void host(Lease lease, InetAddress ip, SSLContext tls, byte[] cert, Host ui)
      throws Exception {
    check(localIPv4(ip), "Choose a private LAN address");
    SSLServerSocket listener =
        lease.track((SSLServerSocket) tls.getServerSocketFactory().createServerSocket());
    listener.setEnabledProtocols(new String[] {"TLSv1.2"});
    listener.bind(new InetSocketAddress(ip, 0), 2);
    byte[] secret = new byte[16];
    RANDOM.nextBytes(secret);
    long expires = now() + INVITE_MS;
    ui.invite(key(ip, listener.getLocalPort(), pin(cert), secret));
    for (int i = 0; i < 5; i++) {
      listener.setSoTimeout(left(expires, 120000));
      SSLSocket sock = lease.track((SSLSocket) listener.accept());
      try {
        Lines io;
        String[] request;
        try {
          sock.setSoTimeout(left(expires, 5000));
          sock.startHandshake();
          io = new Lines(sock);
          request = io.read(Math.min(expires, now() + 5000)).split(" ", -1);
          check(request.length == 3 && request[0].equals("NP1") && request[2].matches("[0-9]{6}"),
              "Invalid request");
          check(MessageDigest.isEqual(secret, Base64.getUrlDecoder().decode(request[1])),
              "Invalid invitation");
        } catch (IOException | IllegalArgumentException bad) {
          continue;
        }
        lease.discard(
            listener); // Consume before presenting consent; no concurrent pending requests.
        long prompt = Math.min(expires, now() + 30000);
        String peer = sock.getInetAddress().getHostAddress();
        if (now() >= prompt || !ui.approve(peer, request[2], prompt) || now() >= prompt
            || lease.closed) {
          io.send("DENIED");
          return;
        }
        io.send("APPROVED VOLUME");
        ui.active(peer);
        long end = now() + SESSION_MS, last = 0;
        while (!lease.closed) {
          String cmd = io.read(Math.min(end, now() + 60000));
          if (cmd.equals("STOP"))
            return;
          check(!lease.closed && now() < end, "Session ended");
          check(cmd.equals("UP") || cmd.equals("DOWN"), "Unsupported command");
          if (now() - last < 300) {
            io.send("WAIT");
            continue;
          }
          last = now();
          io.send(ui.volume(cmd.equals("UP")) ? "OK" : "FAILED");
        }
        return;
      } finally {
        lease.discard(sock);
      }
    }
    throw new IOException("Too many invalid requests");
  }
  static void join(Lease lease, String invite, Controller ui) throws Exception {
    check(invite.matches("NP1-[A-Za-z0-9_-]{51}"), "Paste the complete NP1 device key");
    byte[] b = Base64.getUrlDecoder().decode(invite.substring(4));
    InetAddress ip = InetAddress.getByAddress(Arrays.copyOf(b, 4));
    int port = ((b[4] & 255) << 8) | (b[5] & 255);
    check(localIPv4(ip) && port != 0, "Not a private LAN invite");
    byte[] expected = Arrays.copyOfRange(b, 6, 22);
    X509TrustManager trust = new X509TrustManager() {
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
      public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException {
        throw new CertificateException();
      }
      public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {
        try {
          if (c.length == 0 || !MessageDigest.isEqual(expected, pin(c[0].getEncoded())))
            throw new CertificateException("Wrong host certificate");
        } catch (GeneralSecurityException e) {
          throw new CertificateException("Device key does not match the host", e);
        }
      }
    };
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(null, new TrustManager[] {trust}, RANDOM);
    SSLSocket sock = lease.track((SSLSocket) tls.getSocketFactory().createSocket());
    sock.setEnabledProtocols(new String[] {"TLSv1.2"});
    sock.connect(new InetSocketAddress(ip, port), 5000);
    sock.setSoTimeout(5000);
    sock.startHandshake();
    Lines io = new Lines(sock);
    String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
    io.send("NP1 " + ENCODE.encodeToString(Arrays.copyOfRange(b, 22, 38)) + " " + code);
    ui.pending(code);
    check(io.read(now() + 35000).equals("APPROVED VOLUME"), "Pairing denied");
    ui.active(io);
    long end = now() + SESSION_MS;
    while (!lease.closed) {
      String r = io.read(Math.min(end, now() + 65000));
      check(r.equals("OK") || r.equals("WAIT") || r.equals("FAILED"), "Unsupported response");
      ui.result(r);
    }
  }
}
