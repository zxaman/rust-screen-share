package io.nodeport.link;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Receive-only scan. Invitations and identity pins are never broadcast. */
final class LanDiscovery {
  static final int PORT = 48173;
  static final class Peer {
    final InetSocketAddress target;
    final String name;
    Peer(InetAddress addr, int port, String name) {
      this.target = new InetSocketAddress(addr, port);
      this.name = name;
    }
    boolean matches(String key) {
      if (!key.matches("NP1-[A-Za-z0-9_-]{51}"))
        return false;
      byte[] data = Base64.getUrlDecoder().decode(key.substring(4));
      return Arrays.equals(Arrays.copyOf(data, 4), target.getAddress().getAddress())
          && ((data[4] & 255) * 256 + (data[5] & 255)) == target.getPort();
    }
  }
  static boolean sameNetwork(InetAddress local, InetAddress source, int bits) {
    if (!(source instanceof Inet4Address) || !(local instanceof Inet4Address) || bits < 1
        || bits > 32 || local.equals(source))
      return false;
    byte[] a = local.getAddress(), b = source.getAddress();
    for (int i = 0; i < bits; i++)
      if (((a[i / 8] ^ b[i / 8]) & (128 >>> (i % 8))) != 0)
        return false;
    return true;
  }
  static Peer decode(DatagramPacket packet, InetAddress local, int bits) {
    if (packet.getLength() > 80 || !sameNetwork(local, packet.getAddress(), bits))
      return null;
    String[] p = new String(
        packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.US_ASCII)
                     .split(" ", 3);
    if (p.length != 3 || !p[0].equals("NP-HOST/1") || !p[1].matches("[1-9][0-9]{0,4}")
        || !p[2].matches("[a-zA-Z0-9._ -]{1,48}") || p[2].trim().isEmpty())
      return null;
    int port = Integer.parseInt(p[1]);
    return port > 65535 ? null : new Peer(packet.getAddress(), port, p[2]);
  }
  interface Bind {
    void socket(DatagramSocket s) throws IOException;
  }
  static InetAddress broadcast(InetAddress ip, int prefix) throws IOException {
    if (!(ip instanceof Inet4Address) || prefix < 1 || prefix > 30)
      throw new IOException("This interface has no broadcast subnet. Use the QR or direct key.");
    byte[] b = ip.getAddress();
    for (int bit = prefix; bit < 32; bit++) b[bit / 8] |= (byte) (128 >>> (bit % 8));
    return InetAddress.getByAddress(b);
  }
  static List<Peer> scan(PairLink.Lease lease, InetAddress local, int prefix, Bind bind)
      throws IOException {
    MulticastSocket sock = lease.track(new MulticastSocket(null));
    try {
      sock.setReuseAddress(true);
      sock.bind(new InetSocketAddress(PORT));
      bind.socket(sock);
      try {
        sock.joinGroup(
            new InetSocketAddress("239.255.43.73", PORT), NetworkInterface.getByInetAddress(local));
      } catch (IOException unsupported) { /* broadcast remains available */
      }
      return collect(lease, sock, local, prefix, 6000);
    } finally {
      lease.discard(sock);
    }
  }
  static List<Peer> collect(PairLink.Lease lease, DatagramSocket sock, InetAddress local, int bits,
      int duration) throws IOException {
    Map<InetSocketAddress, Peer> peers = new LinkedHashMap<>();
    long end = PairLink.now() + duration;
    byte[] buf = new byte[81];
    while (!lease.closed && PairLink.now() < end && peers.size() < 32) {
      sock.setSoTimeout((int) Math.max(1, end - PairLink.now()));
      DatagramPacket p = new DatagramPacket(buf, buf.length);
      try {
        sock.receive(p);
      } catch (SocketTimeoutException timeout) {
        break;
      }
      Peer peer = decode(p, local, bits);
      if (peer != null)
        peers.put(peer.target, peer);
    }
    return new ArrayList<>(peers.values());
  }
  static void announce(PairLink.Lease lease, AtomicBoolean open, InetAddress local,
      InetAddress broadcast, int port, String name) throws Exception {
    announce(lease, open, local, broadcast, port, name, s -> {});
  }
  static void announce(PairLink.Lease lease, AtomicBoolean open, InetAddress local,
      InetAddress broadcast, int port, String name, Bind bind) throws Exception {
    String label = name.replaceAll("[^a-zA-Z0-9._ -]", "_").trim();
    if (label.isEmpty())
      label = "Nodeport";
    if (label.length() > 48)
      label = label.substring(0, 48);
    byte[] msg = ("NP-HOST/1 " + port + " " + label).getBytes(StandardCharsets.US_ASCII);
    DatagramPacket p = new DatagramPacket(msg, msg.length, broadcast, PORT);
    MulticastSocket sock = lease.track(new MulticastSocket(new InetSocketAddress(local, 0)));
    try {
      bind.socket(sock);
      sock.setBroadcast(true);
      DatagramPacket mc =
          new DatagramPacket(msg, msg.length, InetAddress.getByName("239.255.43.73"), PORT);
      boolean multicast = true;
      try {
        NetworkInterface nic = NetworkInterface.getByInetAddress(local);
        if (nic == null)
          multicast = false;
        else {
          sock.setNetworkInterface(nic);
          sock.setTimeToLive(1);
        }
      } catch (IOException e) {
        multicast = false;
      }
      long end = PairLink.now() + PairLink.INVITE_MS;
      while (!lease.closed && open.get() && PairLink.now() < end) {
        boolean sent = false;
        try {
          sock.send(p);
          sent = true;
        } catch (IOException e) {
          if (!multicast)
            throw e;
        }
        if (multicast)
          try {
            sock.send(mc);
          } catch (IOException e) {
            if (!sent)
              throw e;
          }
        Thread.sleep(750);
      }
    } finally {
      lease.discard(sock);
    }
  }
}
