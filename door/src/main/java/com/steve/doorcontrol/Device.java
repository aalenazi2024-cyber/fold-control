package com.steve.doorcontrol;

import org.json.JSONObject;

/** One Hikvision unit: a villa door station, face-recognition station or indoor monitor. */
public final class Device {
    public String id;
    public String name;
    public String host;
    public int port = 80;
    public String user = "admin";
    /** Plain text in memory only; stored encrypted by DeviceStore. */
    public String password = "";
    /** How many lock relays to offer (door stations usually have 1-2). */
    public int doors = 2;

    public String label() { return name == null || name.isEmpty() ? host : name; }

    JSONObject toJson(String encryptedPassword) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("host", host)
                .put("port", port)
                .put("user", user)
                .put("pw", encryptedPassword)
                .put("doors", doors);
    }

    static Device fromJson(JSONObject o) {
        Device d = new Device();
        d.id = o.optString("id");
        d.name = o.optString("name");
        d.host = o.optString("host");
        d.port = o.optInt("port", 80);
        d.user = o.optString("user", "admin");
        d.doors = Math.max(1, o.optInt("doors", 2));
        return d;
    }
}
