package moe.vitamin.minecraft.mcp.agent.core;

/** Transport security for the agent's HTTP endpoint. */
public record TlsSettings(
        boolean enabled, String keystore, String keystorePassword, boolean terminatedUpstream) {

    public TlsSettings {
        keystore = keystore == null ? "" : keystore.trim();
        keystorePassword = keystorePassword == null ? "" : keystorePassword;
    }

    public static TlsSettings disabled() {
        return new TlsSettings(false, "", "", false);
    }

    /** Whether the endpoint is protected in transit, by either route. */
    public boolean isProtected() {
        return enabled || terminatedUpstream;
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        if (keystore.isEmpty()) {
            throw new IllegalStateException(
                    "tls.enabled is true but tls.keystore is not set. Point it at a PKCS#12 file "
                            + "holding your certificate and key.");
        }
        if (!java.nio.file.Files.isReadable(java.nio.file.Path.of(keystore))) {
            throw new IllegalStateException("tls.keystore is not readable: " + keystore);
        }
    }
}
