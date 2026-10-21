package sa.urtv.screensaver;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Read-only bridge for the current music session; it never reads notification text or controls playback. */
final class NowPlayingBridge {
    private final Context context;

    NowPlayingBridge(Context context) { this.context = context.getApplicationContext(); }

    @JavascriptInterface
    public String snapshot() {
        JSONObject out = new JSONObject();
        boolean access = hasAccess();
        try {
            out.put("access", access);
            if (!access) return out.toString();

            MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return out.toString();
            ComponentName listener = new ComponentName(context, NowPlayingListener.class);
            List<MediaController> sessions = manager.getActiveSessions(listener);
            MediaController chosen = null;
            PlaybackState chosenState = null;
            for (MediaController session : sessions) {
                MediaMetadata metadata = session.getMetadata();
                if (metadata == null || TextUtils.isEmpty(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))) continue;
                PlaybackState state = session.getPlaybackState();
                int value = state == null ? PlaybackState.STATE_NONE : state.getState();
                if (value == PlaybackState.STATE_PLAYING) { chosen = session; chosenState = state; break; }
                if (chosen == null && (value == PlaybackState.STATE_PAUSED || value == PlaybackState.STATE_BUFFERING)) {
                    chosen = session; chosenState = state;
                }
            }
            if (chosen == null) return out.toString();

            MediaMetadata metadata = chosen.getMetadata();
            long duration = Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            long position = chosenState == null ? 0 : Math.max(0, chosenState.getPosition());
            boolean playing = chosenState != null && chosenState.getState() == PlaybackState.STATE_PLAYING;
            if (playing && chosenState.getLastPositionUpdateTime() > 0) {
                position += Math.round((SystemClock.elapsedRealtime() - chosenState.getLastPositionUpdateTime()) * chosenState.getPlaybackSpeed());
            }
            if (duration > 0) position = Math.min(position, duration);

            out.put("title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            out.put("artist", first(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)));
            out.put("album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
            out.put("duration", duration);
            out.put("position", position);
            out.put("playing", playing);
            String art = artwork(metadata);
            if (art != null) out.put("art", "data:image/jpeg;base64," + art);
        } catch (SecurityException e) {
            try { out.put("access", false); } catch (Exception ignored) {}
        } catch (Exception ignored) {
            // An unavailable player should leave the card in its empty state.
        }
        return out.toString();
    }

    private boolean hasAccess() {
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(new ComponentName(context, NowPlayingListener.class).flattenToString());
    }

    private static String first(String a, String b) { return !TextUtils.isEmpty(a) ? a : b; }

    private static String artwork(MediaMetadata metadata) {
        Bitmap image = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (image == null) image = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (image == null) image = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (image == null) return null;
        Bitmap scaled = image;
        int max = Math.max(image.getWidth(), image.getHeight());
        if (max > 320) {
            float ratio = 320f / max;
            scaled = Bitmap.createScaledBitmap(image, Math.max(1, Math.round(image.getWidth() * ratio)),
                Math.max(1, Math.round(image.getHeight() * ratio)), true);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 78, bytes);
        if (scaled != image) scaled.recycle();
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }
}
