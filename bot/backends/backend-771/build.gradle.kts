plugins {
    id("vitaminmcp.bot-backend")
}

dependencies {
    // The protocol this backend exists for: 771, which is 1.21.6.
    implementation("org.geysermc.mcprotocollib:protocol:1.21.6-1")
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")
}
