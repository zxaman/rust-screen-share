package io.nodeport.link;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.google.zxing.ReaderException;
import java.util.concurrent.*;

/** Foreground QR reader: reads luma only, no frame transmission or storage. */
@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceHolder.Callback {
  private SurfaceView preview;
  private TextView hint;
  private Camera camera;
  private boolean resumed, surface;
  private final ExecutorService decoder = Executors.newSingleThreadExecutor();
  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setFitsSystemWindows(true);
    hint = new TextView(this);
    hint.setText("Point at the host’s Nodeport QR code. Nothing is recorded.");
    box.addView(hint);
    preview = new SurfaceView(this);
    box.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
    preview.getHolder().addCallback(this);
    Button cancel = new Button(this);
    cancel.setText("Cancel / enter key instead");
    cancel.setOnClickListener(v -> finish());
    box.addView(cancel);
    setContentView(box);
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.CAMERA}, 1);
  }
  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] g) {
    super.onRequestPermissionsResult(r, p, g);
    if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED)
      start();
    else
      hint.setText("Camera permission was not granted. Go back and paste the key instead.");
  }
  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    start();
  }
  @Override
  protected void onPause() {
    resumed = false;
    release();
    super.onPause();
  }
  @Override
  protected void onDestroy() {
    decoder.shutdownNow();
    super.onDestroy();
  }
  public void surfaceCreated(SurfaceHolder h) {
    surface = true;
    start();
  }
  public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
  public void surfaceDestroyed(SurfaceHolder h) {
    surface = false;
    release();
  }
  private void release() {
    Camera c = camera;
    camera = null;
    if (c != null) {
      try {
        c.setPreviewCallback(null);
        c.stopPreview();
      } catch (RuntimeException ignored) {
      }
      c.release();
    }
  }
  private void start() {
    if (!surface || !resumed || camera != null
        || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      return;
    try {
      int id = 0;
      Camera.CameraInfo info = new Camera.CameraInfo();
      for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
        Camera.getCameraInfo(i, info);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
          id = i;
          break;
        }
      }
      Camera.getCameraInfo(id, info);
      camera = Camera.open(id);
      Camera.Parameters params = camera.getParameters();
      params.setPreviewFormat(ImageFormat.NV21);
      Camera.Size best = null;
      for (Camera.Size s : params.getSupportedPreviewSizes()) {
        if (best == null || Math.abs(s.width - 1280) < Math.abs(best.width - 1280))
          best = s;
      }
      if (best != null)
        params.setPreviewSize(best.width, best.height);
      if (params.getSupportedFocusModes() != null
          && params.getSupportedFocusModes().contains(
              Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
        params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
      camera.setParameters(params);
      int deg = getWindowManager().getDefaultDisplay().getRotation() * 90;
      camera.setDisplayOrientation(info.facing == Camera.CameraInfo.CAMERA_FACING_BACK
              ? (info.orientation - deg + 360) % 360
              : (360 - (info.orientation + deg) % 360) % 360);
      camera.setPreviewDisplay(preview.getHolder());
      camera.startPreview();
      next(camera);
    } catch (Exception ex) {
      release();
      hint.setText("Could not open camera. Close other camera apps or enter the key manually.");
    }
  }
  private void next(Camera expected) {
    if (camera != expected || !resumed || isFinishing())
      return;
    expected.setOneShotPreviewCallback((data, c) -> {
      if (camera != c || data == null || decoder.isShutdown())
        return;
      Camera.Size size = c.getParameters().getPreviewSize();
      decoder.execute(() -> {
        String value = null;
        try {
          value = InviteQr.decode(data, size.width, size.height);
        } catch (ReaderException | IllegalArgumentException ignored) {
        }
        final String key = value;
        runOnUiThread(() -> {
          if (camera != c || !resumed || isFinishing())
            return;
          if (key != null && PairLink.validInvite(key)) {
            setResult(RESULT_OK, new Intent().putExtra("key", key));
            finish();
          } else {
            if (key != null)
              hint.setText("Not a Nodeport invitation. Scan the current host’s QR.");
            preview.postDelayed(() -> next(c), 100);
          }
        });
      });
    });
  }
}
