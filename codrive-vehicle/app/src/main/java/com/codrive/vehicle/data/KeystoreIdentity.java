package com.codrive.vehicle.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

public class KeystoreIdentity {
    private static final String ALIAS = "codrive_vehicle_identity";
    private static final String PREFS = "codrive_identity";

    private final SharedPreferences prefs;

    public KeystoreIdentity(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private KeyStore store() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks;
    }

    private void ensureKey() throws Exception {
        KeyStore ks = store();
        if (ks.containsAlias(ALIAS)) {
            return;
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());
        g.generateKeyPair();
    }

    public String publicKeyBase64() throws Exception {
        ensureKey();
        Certificate cert = store().getCertificate(ALIAS);
        return Base64.encodeToString(cert.getPublicKey().getEncoded(), Base64.NO_WRAP);
    }

    public String sign(String nonce) throws Exception {
        ensureKey();
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign((PrivateKey) store().getKey(ALIAS, null));
        s.update(nonce.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP);
    }

    public String installationId(String serverUrl) {
        return prefs.getString("iid:" + serverUrl, null);
    }

    public void saveInstallationId(String serverUrl, String id) {
        prefs.edit().putString("iid:" + serverUrl, id).apply();
    }
}
