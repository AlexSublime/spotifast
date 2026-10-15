package rocks.spotifast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

public final class PlaybackService extends Service {
    private static final String CHANNEL_ID = "spotifast-playback";
    private static final int NOTIFICATION_ID = 7215;
    private static final String ACTION_TOGGLE = "rocks.spotifast.TOGGLE";
    private static final String ACTION_NEXT = "rocks.spotifast.NEXT";
    private static final String ACTION_PREVIOUS = "rocks.spotifast.PREVIOUS";
    private static volatile PlaybackService current;

    private MediaSession session;
    private boolean playing;
    private boolean foreground;
    private long position;
    private String title = "";
    private String artists = "";
    private String album = "";
    private String artPath;
    private long duration = -1;

    private static native void nativeMediaCommand(int action, long value);

    public static void publish(boolean local, boolean playing, String title, String artists,
                               String album, String artPath, long position, long duration) {
        PlaybackService service = current;
        if (!local || title.isEmpty()) {
            if (service != null) service.stopPlayback();
            return;
        }
        if (service != null) {
            service.updatePlayback(playing, title, artists, album, artPath, position, duration);
            return;
        }
        if (!playing) return;
        SpotifastActivity activity = getActivity();
        if (activity == null) return;
        Intent start = new Intent(activity, PlaybackService.class);
        start.putExtra("playing", playing);
        start.putExtra("title", title);
        start.putExtra("artists", artists);
        start.putExtra("album", album);
        start.putExtra("art", artPath);
        start.putExtra("position", position);
        start.putExtra("duration", duration);
        if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(start);
        else activity.startService(start);
    }

    private static SpotifastActivity getActivity() {
        return SpotifastActivity.currentActivity();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
        createChannel();
        session = new MediaSession(this, "Spotifast");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { nativeMediaCommand(1, 0); }
            @Override public void onPause() { nativeMediaCommand(2, 0); }
            @Override public void onSkipToNext() { nativeMediaCommand(4, 0); }
            @Override public void onSkipToPrevious() { nativeMediaCommand(5, 0); }
            @Override public void onSeekTo(long target) { nativeMediaCommand(6, target - position); }
            @Override public void onFastForward() { nativeMediaCommand(6, 15000); }
            @Override public void onRewind() { nativeMediaCommand(6, -15000); }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_TOGGLE.equals(action)) nativeMediaCommand(3, 0);
        else if (ACTION_NEXT.equals(action)) nativeMediaCommand(4, 0);
        else if (ACTION_PREVIOUS.equals(action)) nativeMediaCommand(5, 0);
        else updatePlayback(
                intent.getBooleanExtra("playing", false),
                intent.getStringExtra("title"), intent.getStringExtra("artists"),
                intent.getStringExtra("album"), intent.getStringExtra("art"),
                intent.getLongExtra("position", 0), intent.getLongExtra("duration", 0));
        return START_NOT_STICKY;
    }

    private void updatePlayback(boolean isPlaying, String title, String artists, String album,
                                String artPath, long currentPosition, long duration) {
        boolean metadataChanged = !this.title.equals(title) || !this.artists.equals(artists)
                || !this.album.equals(album)
                || (this.artPath == null ? artPath != null : !this.artPath.equals(artPath))
                || this.duration != duration;
        boolean playbackChanged = playing != isPlaying;
        playing = isPlaying;
        position = Math.max(0, currentPosition);
        session.setActive(true);
        if (metadataChanged) {
            this.title = title;
            this.artists = artists;
            this.album = album;
            this.artPath = artPath;
            this.duration = duration;
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artists)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, duration));
            if (artPath != null) {
                Bitmap cover = BitmapFactory.decodeFile(artPath);
                if (cover != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover);
            }
            session.setMetadata(metadata.build());
        }
        int state = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND)
                .setState(state, position, playing ? 1f : 0f)
                .build());
        if (!foreground) {
            startForeground(NOTIFICATION_ID, notification(title, artists));
            foreground = true;
        } else if (metadataChanged || playbackChanged) {
            getSystemService(NotificationManager.class).notify(
                    NOTIFICATION_ID, notification(title, artists));
        }
    }

    private Notification notification(String title, String artists) {
        Intent open = new Intent(this, SpotifastActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title).setContentText(artists).setContentIntent(content)
                .setCategory(Notification.CATEGORY_TRANSPORT).setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS, 3))
                .addAction(action(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", ACTION_TOGGLE, 4))
                .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT, 5));
        return builder.build();
    }

    private Notification.Action action(int icon, String label, String action, int request) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        PendingIntent pending = PendingIntent.getService(this, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(icon, label, pending).build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Music playback", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void stopPlayback() {
        if (session != null) {
            session.setActive(false);
        }
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        playing = false;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (session != null) session.release();
        if (current == this) current = null;
        super.onDestroy();
    }
}
