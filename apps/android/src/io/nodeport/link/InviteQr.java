package io.nodeport.link;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import io.nayuki.qrcodegen.QrCode;
import java.util.Arrays;

/** The display and the camera path share the same private, local-only payload format. */
final class InviteQr {
  static final class Image {
    final int side;
    final int[] pixels;
    Image(String key) {
      QrCode q = QrCode.encodeText(key, QrCode.Ecc.MEDIUM);
      int scale = 10;
      side = (q.size + 8) * scale;
      pixels = new int[side * side];
      Arrays.fill(pixels, 0xffffffff);
      for (int y = 0; y < q.size; y++)
        for (int x = 0; x < q.size; x++)
          if (q.getModule(x, y)) {
            for (int dy = 0; dy < scale; dy++) {
              int start = ((y + 4) * scale + dy) * side + (x + 4) * scale;
              Arrays.fill(pixels, start, start + scale, 0xff000000);
            }
          }
    }
  }
  static String decode(byte[] y, int width, int height) throws ReaderException {
    LuminanceSource luma =
        new PlanarYUVLuminanceSource(y, width, height, 0, 0, width, height, false);
    return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(luma))).getText();
  }
}
