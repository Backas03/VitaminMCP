plugins {
    id("vitaminmcp.bot-backend")
}

dependencies {
    // The protocol this backend exists for: 768, which is 1.21.2 and 1.21.3.
    //
    // A timestamped snapshot for the same reason as backend-767: MCProtocolLib published no
    // release for this range, and the floating form would change what this module builds
    // without anything in the repository changing.
    implementation("org.geysermc.mcprotocollib:protocol:1.21.2-20241209.154112-6")
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")
}
