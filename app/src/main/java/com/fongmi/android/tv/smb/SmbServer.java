package com.fongmi.android.tv.smb;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persisted SMB server profile. Serialized with org.json (no Gson dependency needed).
 * A share name may be empty; in that case the browser prompts for it at runtime.
 */
public class SmbServer {

    private String id = "";
    private String name = "";
    private String host = "";
    private int port = 445;
    private String share = "";
    private String user = "";
    private String pass = "";
    private String domain = "";

    public SmbServer() {
    }

    public static SmbServer fromJson(JSONObject o) throws JSONException {
        SmbServer s = new SmbServer();
        s.id = o.optString("id", "");
        s.name = o.optString("name", "");
        s.host = o.optString("host", "");
        s.port = o.optInt("port", 445);
        s.share = o.optString("share", "");
        s.user = o.optString("user", "");
        s.pass = o.optString("pass", "");
        s.domain = o.optString("domain", "");
        return s;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("host", host);
        o.put("port", port);
        o.put("share", share);
        o.put("user", user);
        o.put("pass", pass);
        o.put("domain", domain);
        return o;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getShare() {
        return share == null ? "" : share;
    }

    public void setShare(String share) {
        this.share = share;
    }

    public String getUser() {
        return user == null ? "" : user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPass() {
        return pass == null ? "" : pass;
    }

    public void setPass(String pass) {
        this.pass = pass;
    }

    public String getDomain() {
        return domain == null ? "" : domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public boolean isAnonymous() {
        return getUser().isEmpty();
    }

    public boolean isValid() {
        return !getHost().isEmpty() && !getName().isEmpty();
    }

    /** Build an smb:// URL pointing at a file/folder inside the share. */
    public String getFileUrl(String share, String path) {
        StringBuilder sb = new StringBuilder("smb://");
        if (!isAnonymous()) {
            if (!getDomain().isEmpty()) sb.append(getDomain()).append(";");
            sb.append(getUser());
            if (!getPass().isEmpty()) sb.append(":").append(getPass());
            sb.append("@");
        }
        sb.append(getHost());
        if (getPort() > 0 && getPort() != 445) sb.append(":").append(getPort());
        sb.append("/").append(share == null ? "" : share);
        if (path != null && !path.isEmpty()) sb.append("/").append(path);
        return sb.toString();
    }

    public String getDisplay() {
        String tail = getShare().isEmpty() ? "" : "/" + getShare();
        return getName() + " (" + getHost() + tail + ")";
    }
}
