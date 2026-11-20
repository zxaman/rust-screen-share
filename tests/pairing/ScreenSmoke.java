package io.nodeport.link;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetAddress;
import javax.imageio.ImageIO;

/** Real QR decoder and Android wire protocol with test frames, no device capture/input. */
public final class ScreenSmoke {
  static void require(boolean value) {
    if (!value)
      throw new AssertionError();
  }
  public static void main(String[] args) throws Exception {
    String key = new BufferedReader(new InputStreamReader(System.in)).readLine();
    if (args[0].equals("qr")) {
      InviteQr.Image image = new InviteQr.Image(key);
      byte[] y = new byte[image.pixels.length];
      for (int i = 0; i < y.length; i++) y[i] = (byte) image.pixels[i];
      require(InviteQr.decode(y, image.side, image.side).equals(key));
      BufferedImage pc = ImageIO.read(new File(args[1]));
      int w = pc.getWidth(), h = pc.getHeight();
      byte[] win = new byte[w * h];
      for (int i = 0; i < win.length; i++) win[i] = (byte) pc.getRGB(i % w, i / w);
      require(InviteQr.decode(win, w, h).equals(key));
      require(
          PairLink.validInvite(PairLink.clean(key.substring(0, 16) + "\n" + key.substring(16))));
      require(!PairLink.validInvite("https://example.com/"));
      require(LanDiscovery.broadcast(InetAddress.getByName("192.168.9.27"), 23)
              .getHostAddress()
              .equals("192.168.9.255"));
      System.out.println("Android + Windows QR pixel round trips passed");
      return;
    }
    final int[] frames = {0};
    try (PairLink.Lease lease = new PairLink.Lease()) {
      PairLink.join(lease, key, "SCREEN_CLICK", null, new PairLink.ScreenController() {
        public void pending(String code) {
          require(code.matches("[0-9]{6}"));
        }
        public void active(PairLink.Lines io) {}
        public void result(String r) {
          throw new AssertionError();
        }
        public String takeClick() {
          return frames[0] == 1 ? "CLICK 1200 2300" : null;
        }
        public void frame(byte[] jpg) throws Exception {
          BufferedImage frame = ImageIO.read(new ByteArrayInputStream(jpg));
          require(frame != null && frame.getWidth() == 16);
          if (++frames[0] == 2)
            lease.close();
        }
      });
    }
    require(frames[0] == 2);
  }
}
