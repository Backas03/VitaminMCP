package moe.vitamin.minecraft.mcp.agent.core;

/**
 * Transport security for the agent's HTTP endpoint.
 *
 * <p>Needed because this is software other people install. A bearer token that grants
 * {@code command_exec} is a console password, and sending one over plaintext HTTP across a
 * network hands it to anyone on the path. On loopback that does not arise — nothing leaves the
 * machine — which is why the agent shipped without TLS and still defaults to loopback.
 *
 * <p>Two ways to be safe once the endpoint is reachable from elsewhere, and the agent refuses
 * to start without one of them (see {@link AgentSettings#validate()}):
 *
 * <ul>
 *   <li>{@code enabled} — the agent serves HTTPS itself from a PKCS#12 keystore.
 *   <li>{@code terminatedUpstream} — something in front already terminates TLS: nginx, Caddy,
 *       a tunnel, a load balancer. The agent then serves plaintext to that proxy only, and the
 *       operator is asserting the hop in between is not the public internet.
 * </ul>
 *
 * <p>No self-signed certificate is generated automatically. It would be the convenient thing
 * and it would teach every client to skip verification, which removes the protection the
 * certificate was for.
 *
 * @param enabled            serve HTTPS directly
 * @param keystore           path to a PKCS#12 keystore holding the certificate and key
 * @param keystorePassword   password for that keystore
 * @param terminatedUpstream TLS is handled by something in front of this agent
 */
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
