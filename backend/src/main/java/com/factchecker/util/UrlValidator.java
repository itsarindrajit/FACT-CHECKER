package com.factchecker.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates and extracts video identifiers from YouTube Shorts and Instagram Reels URLs.
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

    public enum Platform {
        YOUTUBE, INSTAGRAM
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

        return new ValidationResult(false, null, null, null);
    }

    /**
     * Quick check if URL is from a supported platform.
     */
    public static boolean isSupported(String url) {
        return validate(url).valid();
    }
}
