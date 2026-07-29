plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.server-jvm-target")
    id("vitaminmcp.module-rules")
}

// MCP tool schema + DTOs. Pure Java, zero external dependencies — this is what lets the
// agent plugin and mcp-server share a contract without sharing a classpath.
// `checkContractIsDependencyFree` fails the build the moment that stops being true.
//
// Stage 1a fills this in (event/log records, cursor type, pagination wrapper).
