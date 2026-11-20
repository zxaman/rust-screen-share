package io.nodeport.link;

import android.content.Context;
import android.net.*;
import java.io.IOException;
import java.net.*;
import java.util.*;

/** Prefer OS-provided Wi-Fi/Ethernet routes, not a private cellular or VPN IP. */
final class AndroidLan {
  final Network network;
  final InetAddress ip;
  final int prefix;
  final String label;
  AndroidLan(Network n, InetAddress ip, int p, String label) {
    network = n;
    this.ip = ip;
    prefix = p;
    this.label = label;
  }
  static List<AndroidLan> all(Context ctx) throws IOException {
    Map<InetAddress, AndroidLan> out = new LinkedHashMap<>();
    ConnectivityManager cm =
        (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
    for (Network n : cm.getAllNetworks()) {
      NetworkCapabilities c = cm.getNetworkCapabilities(n);
      LinkProperties lp = cm.getLinkProperties(n);
      if (c == null || lp == null
          || (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
              && !c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))
        continue;
      for (LinkAddress a : lp.getLinkAddresses()) {
        InetAddress ip = a.getAddress();
        if (ip instanceof Inet4Address && ip.isSiteLocalAddress())
          out.put(ip, new AndroidLan(n, ip, a.getPrefixLength(), lp.getInterfaceName()));
      }
    }
    // Hotspot and USB Ethernet interfaces need not have a ConnectivityManager Network.
    if (out.isEmpty())
      for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces()))
        if (nic.isUp())
          for (InterfaceAddress a : nic.getInterfaceAddresses()) {
            InetAddress ip = a.getAddress();
            if (ip instanceof Inet4Address && ip.isSiteLocalAddress()
                && a.getNetworkPrefixLength() > 0)
              out.put(ip, new AndroidLan(null, ip, a.getNetworkPrefixLength(), nic.getName()));
          }
    return new ArrayList<>(out.values());
  }
  static void route(Context ctx, Socket s, InetAddress remote) throws IOException {
    for (AndroidLan lan : all(ctx))
      if (lan.network != null && LanDiscovery.sameNetwork(lan.ip, remote, lan.prefix)) {
        lan.network.bindSocket(s);
        return;
      }
  }
  void bind(DatagramSocket sock) throws IOException {
    if (network != null)
      network.bindSocket(sock);
  }
}
