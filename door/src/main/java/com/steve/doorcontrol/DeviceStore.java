package com.steve.doorcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Saved devices. Passwords are encrypted with a key that never leaves the phone's keystore. */
public final class DeviceStore {
    private static final String PREFS = "doors";
    private static final String KEY_ALIAS = "door-control-pw";

    private DeviceStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Device> load(Context c) {
        List<Device> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("devices", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Device d = Device.fromJson(o);
                String enc = o.optString("pw", "");
                try {
                    d.password = enc.isEmpty() ? "" : decrypt(enc);
                } catch (Exception e) {
                    d.password = "";
                }
                out.add(d);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static Device find(Context c, String id) {
        for (Device d : load(c)) if (d.id.equals(id)) return d;
        return null;
    }

    public static void save(Context c, List<Device> devices) throws Exception {
        JSONArray arr = new JSONArray();
        for (Device d : devices) {
            if (d.id == null || d.id.isEmpty()) d.id = UUID.randomUUID().toString();
            arr.put(d.toJson(d.password.isEmpty() ? "" : encrypt(d.password)));
        }
        prefs(c).edit().putString("devices", arr.toString()).apply();
    }

    public static void upsert(Context c, Device device) throws Exception {
        List<Device> all = load(c);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(device.id)) {
                all.set(i, device);
                replaced = true;
            }
        }
        if (!replaced) all.add(device);
        save(c, all);
    }

    public static void delete(Context c, String id) throws Exception {
        List<Device> all = load(c);
        all.removeIf(d -> d.id.equals(id));
        save(c, all);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            return kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    private static String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        String[] parts = stored.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
