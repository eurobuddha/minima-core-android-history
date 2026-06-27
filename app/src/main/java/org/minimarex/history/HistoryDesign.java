package org.minimarex.history;

/** Shared dark + orange palette (matches the native Minima wallet/vestr/expert apps). */
public final class HistoryDesign {
    private HistoryDesign() {}

    public static final int BG        = 0xFF0A0A0F;   // app background
    public static final int SURFACE   = 0xFF15151F;   // header / composer / cards
    public static final int SURFACE_2 = 0xFF1F1F2B;   // raised (rows / detail)
    public static final int BORDER    = 0xFF2A2A38;
    public static final int ACCENT    = 0xFFF7931A;   // orange
    public static final int ON_ACCENT = 0xFF0A0A0F;
    public static final int TEXT      = 0xFFFFFFFF;
    public static final int DIM       = 0xFF9A9AA8;
    public static final int DIM_2     = 0xFF6A6A78;

    public static final int RECEIVED  = 0xFF2ECC71;   // green ↓
    public static final int SENT      = 0xFFFF7A66;   // soft red ↑
    public static final int SELF      = 0xFF9A9AA8;   // grey ⟲

    public static final int ACCENT_SOFT = 0x33F7931A; // faint orange (pairing banner)
}
