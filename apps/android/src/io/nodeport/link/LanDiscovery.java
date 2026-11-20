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
  static InterfaceAddress network(InetAddress local) throws IOException {
    NetworkInterface nic = NetworkInterface.getByInetAddress(local);
    if (nic != null)
      for (InterfaceAddress a : nic.getInterfaceAddresses())
        if (local.equals(a.getAddress()) && a.getBroadcast() != null)
          return a;
    throw new IOException("No broadcast-capable network. Try the direct key instead.");
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
  static List<Peer> scan(PairLink.Lease lease, InetAddress local) throws IOException {
    InterfaceAddress lan = network(local);
    DatagramSocket sock = lease.track(new DatagramSocket(null));
    try {
      sock.setReuseAddress(true);
      sock.bind(new InetSocketAddress(PORT));
      return collect(lease, sock, local, lan.getNetworkPrefixLength(), 4000);
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
  static void advertise(PairLink.Lease lease, AtomicBoolean open, InetAddress local, int port,
      String name) throws Exception {
    announce(lease, open, local, network(local).getBroadcast(), port, name);
  }
  static void announce(PairLink.Lease lease, AtomicBoolean open, InetAddress local,
      InetAddress broadcast, int port, String name) throws Exception {
    String label = name.replaceAll("[^a-zA-Z0-9._ -]", "_").trim();
    if (label.isEmpty())
      label = "Nodeport";
    if (label.length() > 48)
      label = label.substring(0, 48);
    byte[] msg = ("NP-HOST/1 " + port + " " + label).getBytes(StandardCharsets.US_ASCII);
    DatagramPacket p = new DatagramPacket(msg, msg.length, broadcast, PORT);
    DatagramSocket sock = lease.track(new DatagramSocket(new InetSocketAddress(local, 0)));
    try {
      sock.setBroadcast(true);
      long end = PairLink.now() + PairLink.INVITE_MS;
      while (!lease.closed && open.get() && PairLink.now() < end) {
        sock.send(p);
        Thread.sleep(750);
      }
    } finally {
      lease.discard(sock);
    }
  }
}
