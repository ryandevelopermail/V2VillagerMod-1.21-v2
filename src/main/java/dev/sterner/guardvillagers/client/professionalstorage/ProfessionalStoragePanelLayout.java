package dev.sterner.guardvillagers.client.professionalstorage;

/** Pure panel geometry calculations shared by rendering, input handling, and tests. */
public final class ProfessionalStoragePanelLayout {
    public static final int LINE_HEIGHT = 9;
    public static final int LABEL_VALUE_GAP = 2;
    public static final int ROW_GAP = 7;
    public static final int MIN_SCROLLBAR_THUMB_HEIGHT = 12;

    private ProfessionalStoragePanelLayout() {
    }

    public static int rowHeight(int labelLineCount, int valueLineCount) {
        if (labelLineCount < 1 || valueLineCount < 1) {
            throw new IllegalArgumentException("Rendered rows require at least one label and value line");
        }
        return labelLineCount * LINE_HEIGHT
                + LABEL_VALUE_GAP
                + valueLineCount * LINE_HEIGHT
                + ROW_GAP;
    }

    public static int maxScroll(int contentHeight, int viewportHeight) {
        return Math.max(0, contentHeight - Math.max(0, viewportHeight));
    }

    public static int clampScroll(int offset, int maximum) {
        return Math.max(0, Math.min(offset, Math.max(0, maximum)));
    }

    public static int scrollbarThumbHeight(int trackHeight, int contentHeight) {
        if (trackHeight <= 0 || contentHeight <= trackHeight) {
            return Math.max(0, trackHeight);
        }
        int proportional = (int) Math.round((double) trackHeight * trackHeight / contentHeight);
        return Math.min(trackHeight, Math.max(MIN_SCROLLBAR_THUMB_HEIGHT, proportional));
    }

    public static int scrollbarThumbOffset(
            int scrollOffset,
            int maximumScroll,
            int trackHeight,
            int thumbHeight
    ) {
        int travel = Math.max(0, trackHeight - thumbHeight);
        if (maximumScroll <= 0 || travel == 0) {
            return 0;
        }
        return (int) Math.round((double) clampScroll(scrollOffset, maximumScroll) * travel / maximumScroll);
    }

    public static int scrollOffsetForThumb(
            int thumbOffset,
            int maximumScroll,
            int trackHeight,
            int thumbHeight
    ) {
        int travel = Math.max(0, trackHeight - thumbHeight);
        if (maximumScroll <= 0 || travel == 0) {
            return 0;
        }
        int clampedThumb = Math.max(0, Math.min(thumbOffset, travel));
        return (int) Math.round((double) clampedThumb * maximumScroll / travel);
    }

    public static int clampContainerX(int screenWidth, int combinedWidth) {
        if (combinedWidth >= screenWidth) {
            return 0;
        }
        int centered = (screenWidth - combinedWidth) / 2;
        return Math.max(0, Math.min(centered, screenWidth - combinedWidth));
    }
}
