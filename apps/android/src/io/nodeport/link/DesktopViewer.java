package io.nodeport.link;

import android.app.Activity;
import android.graphics.*;
import android.view.MotionEvent;
import android.widget.ImageView;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One decoded frame in flight. No recording, background viewing, or arbitrary input. */
final class DesktopViewer {
  final Activity owner;
  final PairLink.Lease lease;
  final boolean clicks;
  final AtomicReference<String> pending = new AtomicReference<>();
  ImageView view;
  DesktopViewer(Activity owner, PairLink.Lease lease, boolean clicks) {
    this.owner = owner;
    this.lease = lease;
    this.clicks = clicks;
  }
  ImageView create() {
    view = new ImageView(owner);
    view.setScaleType(ImageView.ScaleType.FIT_CENTER);
    view.setBackgroundColor(0xff202020);
    view.setContentDescription("Windows primary display");
    if (clicks)
      view.setOnTouchListener((v, event) -> {
        if (event.getActionMasked() == MotionEvent.ACTION_UP && !lease.closed
            && view.getDrawable() != null) {
          Matrix inverse = new Matrix();
          if (view.getImageMatrix().invert(inverse)) {
            float[] pt = {event.getX(), event.getY()};
            inverse.mapPoints(pt);
            int w = view.getDrawable().getIntrinsicWidth(),
                h = view.getDrawable().getIntrinsicHeight();
            if (pt[0] >= 0 && pt[1] >= 0 && pt[0] < w && pt[1] < h)
              pending.set("CLICK " + Math.min(10000, Math.round(pt[0] * 10000 / Math.max(1, w - 1)))
                  + " " + Math.min(10000, Math.round(pt[1] * 10000 / Math.max(1, h - 1))));
          }
        }
        return true;
      });
    return view;
  }
  void frame(byte[] jpeg) throws Exception {
    BitmapFactory.Options opt = new BitmapFactory.Options();
    opt.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opt);
    if (!"image/jpeg".equals(opt.outMimeType) || opt.outWidth < 1 || opt.outWidth > 960
        || opt.outHeight < 1 || opt.outHeight > 720)
      throw new IOException("Invalid desktop frame");
    opt.inJustDecodeBounds = false;
    Bitmap image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opt);
    if (image == null)
      throw new IOException("Invalid JPEG");
    image.setDensity(Bitmap.DENSITY_NONE);
    CountDownLatch displayed = new CountDownLatch(1);
    owner.runOnUiThread(() -> {
      try {
        if (!lease.closed && view != null)
          view.setImageBitmap(image);
      } finally {
        displayed.countDown();
      }
    });
    if (!displayed.await(3, TimeUnit.SECONDS))
      throw new IOException("Viewer is no longer active");
  }
}
