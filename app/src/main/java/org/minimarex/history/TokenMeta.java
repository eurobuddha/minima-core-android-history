package org.minimarex.history;

import org.json.JSONObject;

import java.net.URLDecoder;

/**
 * Parsed token metadata. Minima token "name" can be a plain string or a JSON object
 * {name, url, description, ticker, ...}, and icon/description may live at either level —
 * so we dig defensively.
 */
public class TokenMeta {

    public String name = "Token";
    public String ticker = "";
    public String iconUrl = "";
    public String description = "";
    public String decimals = "";

    public static TokenMeta parse(Object token, String tokenid) {
        TokenMeta m = new TokenMeta();
        if (Util.isMinima(tokenid)) {
            m.name = "Minima";
            m.ticker = "MINIMA";
            return m;
        }
        if (token instanceof String) {
            m.name = (String) token;
            return m;
        }
        if (token instanceof JSONObject) {
            JSONObject t = (JSONObject) token;
            m.decimals = t.optString("decimals", "");

            JSONObject meta = null;
            Object nameNode = t.opt("name");
            if (nameNode instanceof JSONObject) {
                meta = (JSONObject) nameNode;
                m.name = meta.optString("name", "Token");
            } else if (nameNode instanceof String) {
                m.name = (String) nameNode;
            }

            m.iconUrl = decode(first(
                    meta != null ? meta.optString("url", "") : "",
                    t.optString("url", ""),
                    meta != null ? meta.optString("icon", "") : "",
                    t.optString("icon", "")));
            m.description = first(
                    meta != null ? meta.optString("description", "") : "",
                    t.optString("description", ""));
            m.ticker = first(
                    meta != null ? meta.optString("ticker", "") : "",
                    t.optString("ticker", ""));
        }
        return m;
    }

    private static String first(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    /** URL-decode http(s) urls (token urls are often percent-encoded); leave data: URIs intact. */
    private static String decode(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("data:")) return url;
        if (url.indexOf('%') < 0) return url;
        try { return URLDecoder.decode(url, "UTF-8"); } catch (Exception e) { return url; }
    }
}
