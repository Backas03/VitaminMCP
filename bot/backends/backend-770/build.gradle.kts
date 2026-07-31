plugins {
    id("vitaminmcp.bot-backend")
}

dependencies {
    // The protocol this backend exists for: 770, which is 1.21.5. A published release, unlike
    // the ranges below it — MCProtocolLib's releases start here.
    implementation("org.geysermc.mcprotocollib:protocol:1.21.5-1")
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")
}
