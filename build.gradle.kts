// Aggregator only — the root project holds no source.
//
// Everything shared lives in the `vitaminmcp.*` convention plugins under build-logic/:
//   vitaminmcp.java-conventions    Java 21 toolchain, compiler options, JUnit 5
//   vitaminmcp.module-rules        dependency-direction enforcement
//   vitaminmcp.shadow-conventions  shadow + relocation for jars that run inside a server JVM
