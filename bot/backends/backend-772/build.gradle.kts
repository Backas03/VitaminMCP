plugins {
    id("vitaminmcp.bot-backend")
}

dependencies {
    // The protocol this backend exists for: 772, which is 1.21.7 and 1.21.8. Everything else
    // about the module — its jar name, which source it compiles, how it announces itself — is
    // derived from the directory name by the convention plugin, so a sibling protocol is a
    // directory and this one line.
    implementation("org.geysermc.mcprotocollib:protocol:1.21.7-1")
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")
}
