package com.gathin.tvhome;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages active music playback detection strictly for Spotify, YouTube Music, and Meld.
 */
public final class NowPlayingManager {
    private static final String TAG = "TvHome.NowPlaying";

    public static boolean isAllowedMusicApp(String pkg) {
        if (pkg == null) return false;
        return pkg.equals("com.spotify.tv.android")
                || pkg.equals("com.spotify.music")
                || pkg.equals("com.google.android.apps.youtube.music")
                || pkg.equals("com.google.android.youtube.tvmusic")
                || pkg.equals("it.francescograzioso.meld")
                || pkg.equals("com.francescograzioso.meld");
    }

    public static class MediaInfo {
        public final boolean isPlaying;
        public final String title;
        public final String artist;
        public final String packageName;
        public final String appName;
        public final MediaController controller;
        public final Bitmap albumArt;

        public MediaInfo(boolean isPlaying, String title, String artist, String packageName, String appName, MediaController controller, Bitmap albumArt) {
            this.isPlaying = isPlaying;
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.packageName = packageName != null ? packageName : "";
            this.appName = appName != null ? appName : "";
            this.controller = controller;
            this.albumArt = albumArt;
        }

        public boolean hasMusic() {
            return isPlaying && isAllowedMusicApp(packageName);
        }

        public String getDisplayText() {
            if (!hasMusic()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (isPlaying) {
                sb.append("▶ ");
            } else {
                sb.append("⏸ ");
            }
            if (!title.isEmpty()) {
                sb.append(title);
                if (!artist.isEmpty()) {
                    sb.append(" • ").append(artist);
                }
            } else if (!appName.isEmpty()) {
                sb.append(appName);
            }
            return sb.toString();
        }
    }

    public interface Listener {
        void onMediaChanged(MediaInfo info);
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static MediaInfo currentInfo = new MediaInfo(false, "", "", "", "", null, null);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private static Context appContext;
    private static MediaController trackedController;

    private static final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (appContext != null) updateCurrentMedia(appContext);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            if (appContext != null) updateCurrentMedia(appContext);
        }

        @Override
        public void onSessionDestroyed() {
            trackedController = null;
            if (appContext != null) updateCurrentMedia(appContext);
        }
    };

    private NowPlayingManager() {}

    public static void registerListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            listener.onMediaChanged(currentInfo);
        }
    }

    public static void unregisterListener(Listener listener) {
        listeners.remove(listener);
    }

    public static MediaInfo getCurrentInfo() {
        return currentInfo;
    }

    public static void notifySessionsChanged(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        updateCurrentMedia(appContext != null ? appContext : context);
    }

    public static void notifyListenerConnected(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        registerActiveSessionsListener(appContext);
        updateCurrentMedia(appContext);
    }

    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        registerActiveSessionsListener(appContext);
        updateCurrentMedia(appContext);
    }

    private static void registerActiveSessionsListener(Context context) {
        try {
            MediaSessionManager msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm != null) {
                ComponentName cn = MediaNotificationListener.getComponentName(context);
                if (sessionsListener == null) {
                    sessionsListener = controllers -> updateCurrentMedia(context);
                }
                try {
                    msm.removeOnActiveSessionsChangedListener(sessionsListener);
                } catch (Exception ignored) {}
                try {
                    msm.addOnActiveSessionsChangedListener(sessionsListener, cn, mainHandler);
                } catch (SecurityException ignored) {
                    Log.w(TAG, "Notification listener access not yet granted");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering active sessions listener: " + e.getMessage());
        }
    }

    public static synchronized void updateCurrentMedia(Context context) {
        if (context == null) return;
        if (appContext == null) appContext = context.getApplicationContext();

        try {
            MediaSessionManager msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = MediaNotificationListener.getComponentName(context);

            MediaController activeController = null;
            if (msm != null) {
                try {
                    List<MediaController> controllers = msm.getActiveSessions(cn);
                    if (controllers != null && !controllers.isEmpty()) {
                        // Priority 1: Actively playing controller from supported music apps
                        for (MediaController mc : controllers) {
                            if (mc == null || !isAllowedMusicApp(mc.getPackageName())) continue;
                            PlaybackState state = mc.getPlaybackState();
                            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                                activeController = mc;
                                break;
                            }
                        }
                        // Priority 2: Paused/Buffering controller
                        if (activeController == null) {
                            for (MediaController mc : controllers) {
                                if (mc == null || !isAllowedMusicApp(mc.getPackageName())) continue;
                                PlaybackState state = mc.getPlaybackState();
                                if (state != null && (state.getState() == PlaybackState.STATE_PAUSED
                                        || state.getState() == PlaybackState.STATE_BUFFERING
                                        || state.getState() == PlaybackState.STATE_CONNECTING)) {
                                    activeController = mc;
                                    break;
                                }
                            }
                        }
                    }
                } catch (SecurityException ignored) {
                }
            }

            // Sync controller callback
            if (trackedController != activeController) {
                if (trackedController != null) {
                    try {
                        trackedController.unregisterCallback(controllerCallback);
                    } catch (Exception ignored) {}
                }
                trackedController = activeController;
                if (trackedController != null) {
                    try {
                        trackedController.registerCallback(controllerCallback, mainHandler);
                    } catch (Exception ignored) {}
                }
            }

            String title = "";
            String artist = "";
            String pkg = "";
            Bitmap albumArt = null;
            boolean isPlaying = false;

            if (activeController != null) {
                pkg = activeController.getPackageName();
                PlaybackState state = activeController.getPlaybackState();
                isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;

                MediaMetadata metadata = activeController.getMetadata();
                if (metadata != null) {
                    MediaDescription desc = metadata.getDescription();
                    if (desc != null) {
                        if (desc.getTitle() != null && desc.getTitle().length() > 0) {
                            title = desc.getTitle().toString();
                        }
                        if (desc.getSubtitle() != null && desc.getSubtitle().length() > 0) {
                            artist = desc.getSubtitle().toString();
                        }
                        if (desc.getDescription() != null && artist.isEmpty()) {
                            artist = desc.getDescription().toString();
                        }
                        if (albumArt == null && desc.getIconBitmap() != null) {
                            albumArt = desc.getIconBitmap();
                        }
                    }

                    if (title.isEmpty()) {
                        CharSequence titleCs = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
                        if (titleCs != null && titleCs.length() > 0) {
                            title = titleCs.toString();
                        }
                    }

                    if (artist.isEmpty()) {
                        CharSequence artistCs = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
                        if (artistCs == null || artistCs.length() == 0) {
                            artistCs = metadata.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                        }
                        if (artistCs == null || artistCs.length() == 0) {
                            artistCs = metadata.getText(MediaMetadata.METADATA_KEY_AUTHOR);
                        }
                        if (artistCs != null && artistCs.length() > 0) {
                            artist = artistCs.toString();
                        }
                    }

                    if (albumArt == null) {
                        try {
                            albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                            if (albumArt == null) {
                                albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            // Fallback: check notification extras for supported music apps
            if (MediaNotificationListener.isConnected()) {
                try {
                    StatusBarNotification[] sbns = MediaNotificationListener.getInstance().getActiveNotifications();
                    if (sbns != null) {
                        for (StatusBarNotification sbn : sbns) {
                            if (sbn == null || sbn.getNotification() == null) continue;
                            String sbnPkg = sbn.getPackageName();
                            if (!isAllowedMusicApp(sbnPkg)) continue;

                            Bundle extras = sbn.getNotification().extras;
                            if (extras != null) {
                                CharSequence nTitle = extras.getCharSequence(Notification.EXTRA_TITLE);
                                CharSequence nText = extras.getCharSequence(Notification.EXTRA_TEXT);
                                if (nTitle != null && nTitle.length() > 0) {
                                    if (title.isEmpty()) {
                                        if (!"Spotify".equalsIgnoreCase(nTitle.toString()) && !"YouTube".equalsIgnoreCase(nTitle.toString()) && !"Meld".equalsIgnoreCase(nTitle.toString())) {
                                            title = nTitle.toString();
                                            if (nText != null && !"Spotify".equalsIgnoreCase(nText.toString())) {
                                                artist = nText.toString();
                                            }
                                        } else if (nText != null && !"Spotify".equalsIgnoreCase(nText.toString())) {
                                            title = nText.toString();
                                        }
                                        if (pkg.isEmpty()) pkg = sbnPkg;
                                    }
                                    if (albumArt == null) {
                                        Object iconObj = extras.get(Notification.EXTRA_LARGE_ICON);
                                        if (iconObj instanceof Bitmap) {
                                            albumArt = (Bitmap) iconObj;
                                        } else if (iconObj instanceof Icon) {
                                            try {
                                                Drawable d = ((Icon) iconObj).loadDrawable(context);
                                                if (d instanceof BitmapDrawable) {
                                                    albumArt = ((BitmapDrawable) d).getBitmap();
                                                }
                                            } catch (Exception ignored) {}
                                        }
                                    }
                                    if (activeController == null) {
                                        Object tokenObj = extras.get(Notification.EXTRA_MEDIA_SESSION);
                                        if (tokenObj instanceof MediaSession.Token) {
                                            try {
                                                activeController = new MediaController(context, (MediaSession.Token) tokenObj);
                                                PlaybackState ps = activeController.getPlaybackState();
                                                isPlaying = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
                                            } catch (Exception ignored) {}
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (pkg.isEmpty() && activeController != null) {
                pkg = activeController.getPackageName();
            }

            if (activeController == null && title.isEmpty()) {
                currentInfo = new MediaInfo(false, "", "", "", "", null, null);
            } else if (!isAllowedMusicApp(pkg)) {
                currentInfo = new MediaInfo(false, "", "", "", "", null, null);
            } else {
                String appName = getAppName(context, pkg);
                currentInfo = new MediaInfo(isPlaying, title, artist, pkg, appName, activeController, albumArt);
            }

            mainHandler.post(() -> {
                for (Listener l : listeners) {
                    l.onMediaChanged(currentInfo);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error updating current media: " + e.getMessage());
        }
    }

    public static String getAppName(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            if ("com.spotify.tv.android".equals(pkg) || "com.spotify.music".equals(pkg)) return "Spotify";
            if (pkg.contains("youtube")) return "YouTube Music";
            if (pkg.contains("meld")) return "Meld";
            return pkg;
        }
    }

    public static void togglePlayPause() {
        if (currentInfo != null && currentInfo.controller != null) {
            try {
                PlaybackState state = currentInfo.controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    currentInfo.controller.getTransportControls().pause();
                } else {
                    currentInfo.controller.getTransportControls().play();
                }
                return;
            } catch (Exception e) {
                Log.e(TAG, "Error toggling play/pause via controller: " + e.getMessage());
            }
        }
        if (appContext != null) {
            try {
                AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                }
            } catch (Exception ignored) {}
        }
    }

    public static void pausePlayback() {
        if (currentInfo != null && currentInfo.controller != null) {
            try {
                currentInfo.controller.getTransportControls().pause();
                return;
            } catch (Exception e) {
                Log.e(TAG, "Error pausing via controller: " + e.getMessage());
            }
        }
        if (appContext != null) {
            try {
                AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
                }
            } catch (Exception ignored) {}
        }
    }

    public static void skipToNext() {
        if (currentInfo != null && currentInfo.controller != null) {
            try {
                currentInfo.controller.getTransportControls().skipToNext();
                return;
            } catch (Exception ignored) {}
        }
        if (appContext != null) {
            try {
                AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT));
                }
            } catch (Exception ignored) {}
        }
    }

    public static void skipToPrevious() {
        if (currentInfo != null && currentInfo.controller != null) {
            try {
                currentInfo.controller.getTransportControls().skipToPrevious();
                return;
            } catch (Exception ignored) {}
        }
        if (appContext != null) {
            try {
                AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
                }
            } catch (Exception ignored) {}
        }
    }

    public static void openMediaApp(Context context) {
        if (context == null || currentInfo == null) return;
        if (currentInfo.controller != null) {
            PendingIntent pi = currentInfo.controller.getSessionActivity();
            if (pi != null) {
                try {
                    pi.send();
                    return;
                } catch (Exception ignored) {}
            }
        }
        String pkg = currentInfo.packageName.isEmpty() ? "it.francescograzioso.meld" : currentInfo.packageName;
        try {
            Intent i = context.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) {
                i = context.getPackageManager().getLaunchIntentForPackage(pkg);
            }
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            }
        } catch (Exception ignored) {}
    }
}
