plugins {
    id("vitaminmcp.bot-backend")
}

dependencies {
    // The protocol this backend exists for: 767, which is 1.21 and 1.21.1.
    //
    // A timestamped snapshot, not a release, because MCProtocolLib published none for this
    // range — `1.21-SNAPSHOT` is all that exists. The timestamp is what makes it reproducible:
    // the floating form would resolve to whatever is newest and change what this module builds
    // without anything in the repository changing, which for something other people install is
    // not a trade worth making.
    implementation("org.geysermc.mcprotocollib:protocol:1.21-20241010.155958-24")
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")
}
