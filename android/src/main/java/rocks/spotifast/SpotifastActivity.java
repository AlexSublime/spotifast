package rocks.spotifast;

import android.app.NativeActivity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import android.view.KeyEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.io.FilenameFilter;

public final class SpotifastActivity extends NativeActivity {
    private static final int REQUEST_COVER = 7214;
    private static volatile WeakReference<SpotifastActivity> current = new WeakReference<>(null);
    private long coverRequest;

    private static native void nativeCoverPicked(long request, String path, String error);

    static SpotifastActivity currentActivity() {
        return current.get();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        File[] oldFiles = getCacheDir().listFiles(new FilenameFilter() {
            @Override public boolean accept(File dir, String name) {
                return name.startsWith("spotifast-cover-");
            }
        });
        if (oldFiles != null) {
            for (File file : oldFiles) file.delete();
        }
    }

    @Override
    protected void onDestroy() {
        if (coverRequest != 0) nativeCoverPicked(coverRequest, null, null);
        if (current.get() == this) current.clear();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) moveTaskToBack(true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    public static void openUrl(String value) {
        SpotifastActivity activity = current.get();
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value)));
                } catch (ActivityNotFoundException | SecurityException error) {
                    Toast.makeText(activity, "No browser can open this link.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    public static boolean chooseCover(long request) {
        SpotifastActivity activity = current.get();
        if (activity == null) return false;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.startCoverPicker(request); }
        });
        return true;
    }

    private void startCoverPicker(long request) {
        if (coverRequest != 0) {
            nativeCoverPicked(request, null, "Another image picker is already open.");
            return;
        }
        coverRequest = request;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/jpeg", "image/png"});
        try {
            startActivityForResult(intent, REQUEST_COVER);
        } catch (ActivityNotFoundException | SecurityException error) {
            coverRequest = 0;
            nativeCoverPicked(request, null, "Android could not open the image picker.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_COVER || coverRequest == 0) return;
        long request = coverRequest;
        coverRequest = 0;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            nativeCoverPicked(request, null, null);
            return;
        }
        File copy = null;
        try (InputStream input = getContentResolver().openInputStream(data.getData())) {
            if (input == null) throw new IllegalStateException("Image cannot be read.");
            copy = File.createTempFile("spotifast-cover-", ".img", getCacheDir());
            try (FileOutputStream output = new FileOutputStream(copy)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            nativeCoverPicked(request, copy.getAbsolutePath(), null);
        } catch (Exception error) {
            if (copy != null) copy.delete();
            nativeCoverPicked(request, null, "Could not read that image.");
        }
    }
}
