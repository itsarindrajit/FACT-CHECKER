package com.factchecker.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates and extracts video identifiers from YouTube Shorts, Instagram Reels,
 * and Facebook Reels URLs.
 */
public final class UrlValidator {

    private UrlValidator() {}

    // YouTube Shorts patterns
    private static final Pattern YT_SHORTS_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?(?:youtube\\.com/shorts/|youtu\\.be/)([a-zA-Z0-9_-]{11})"
    );

    // YouTube regular video pattern (also support standard URLs)
    private static final Pattern YT_VIDEO_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?(?:youtube\\.com/watch\\?v=|youtu\\.be/)([a-zA-Z0-9_-]{11})"
    );

    // Instagram Reels patterns
    private static final Pattern INSTA_REEL_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?instagram\\.com/(?:reel|reels)/([a-zA-Z0-9_-]+)"
    );

    // Facebook Reels patterns
    // Matches: facebook.com/reel/123, facebook.com/reels/123, fb.watch/xxx
    private static final Pattern FB_REEL_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?(?:facebook\\.com|fb\\.com)/(?:reel|reels)/([0-9]+)"
    );

    // Facebook short link pattern: fb.watch/xxxxx
    private static final Pattern FB_WATCH_PATTERN = Pattern.compile(
            "(?:https?://)?fb\\.watch/([a-zA-Z0-9_-]+)"
    );

    // Facebook video pattern: facebook.com/watch?v=123 or facebook.com/video/123
    private static final Pattern FB_VIDEO_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?(?:facebook\\.com|fb\\.com)/(?:watch\\?v=|video(?:s)?/)([0-9]+)"
    );

    public enum Platform {
        YOUTUBE, INSTAGRAM, FACEBOOK
    }

    public record ValidationResult(boolean valid, Platform platform, String videoId, String normalizedUrl) {}

    /**
     * Validates the given URL and returns a result with platform info and video ID.
     */
    public static ValidationResult validate(String url) {
        if (url == null || url.isBlank()) {
            return new ValidationResult(false, null, null, null);
        }

        String trimmed = url.trim();

        // Check YouTube Shorts first
        Matcher ytShortsMatcher = YT_SHORTS_PATTERN.matcher(trimmed);
        if (ytShortsMatcher.find()) {
            String videoId = ytShortsMatcher.group(1);
            return new ValidationResult(true, Platform.YOUTUBE, videoId,
                    "https://www.youtube.com/shorts/" + videoId);
        }

        // Check standard YouTube URLs
        Matcher ytVideoMatcher = YT_VIDEO_PATTERN.matcher(trimmed);
        if (ytVideoMatcher.find()) {
            String videoId = ytVideoMatcher.group(1);
            return new ValidationResult(true, Platform.YOUTUBE, videoId,
                    "https://www.youtube.com/watch?v=" + videoId);
        }

        // Check Instagram Reels
        Matcher instaReelMatcher = INSTA_REEL_PATTERN.matcher(trimmed);
        if (instaReelMatcher.find()) {
            String reelId = instaReelMatcher.group(1);
            return new ValidationResult(true, Platform.INSTAGRAM, reelId,
                    "https://www.instagram.com/reel/" + reelId);
        }

        // Check Facebook Reels
        Matcher fbReelMatcher = FB_REEL_PATTERN.matcher(trimmed);
        if (fbReelMatcher.find()) {
            String reelId = fbReelMatcher.group(1);
            return new ValidationResult(true, Platform.FACEBOOK, reelId,
                    "https://www.facebook.com/reel/" + reelId);
        }

        // Check Facebook short links (fb.watch/xxx)
        Matcher fbWatchMatcher = FB_WATCH_PATTERN.matcher(trimmed);
        if (fbWatchMatcher.find()) {
            String watchId = fbWatchMatcher.group(1);
            return new ValidationResult(true, Platform.FACEBOOK, watchId,
                    "https://fb.watch/" + watchId);
        }

        // Check Facebook video URLs
        Matcher fbVideoMatcher = FB_VIDEO_PATTERN.matcher(trimmed);
        if (fbVideoMatcher.find()) {
            String videoId = fbVideoMatcher.group(1);
            return new ValidationResult(true, Platform.FACEBOOK, videoId,
                    "https://www.facebook.com/video/" + videoId);
        }

        return new ValidationResult(false, null, null, null);
    }

    /**
     * Quick check if URL is from a supported platform.
     */
    public static boolean isSupported(String url) {
        return validate(url).valid();
    }
}

