package moe.vitamin.minecraft.mcp.testkit;

import java.util.List;
import java.util.Objects;

/**
 * How one scenario fared across every version.
 *
 * <p>The interesting output is rarely "it passed" — it is <em>which</em> versions failed, since
 * that is what separates a broken plugin from a version-specific problem. A run where every
 * version fails identically points at the scenario or the plugin; one where a single version
 * fails points at that version.
 *
 * @param results one entry per version, in the order the matrix listed them
 */
public record MatrixResult(List<VersionOutcome> results) {

    public MatrixResult {
        results = List.copyOf(Objects.requireNonNull(results, "results"));
    }

    public boolean allPassed() {
        return results.stream().allMatch(VersionOutcome::passed);
    }

    /** Versions where the scenario did not pass. */
    public List<VersionOutcome> failures() {
        return results.stream().filter(outcome -> !outcome.passed()).toList();
    }

    /**
     * A table, because the shape of the failures is the finding.
     *
     * <p>Printed even when everything passed: knowing which versions were actually covered
     * matters as much as the verdict, and a bare "passed" hides a matrix that ran one version.
     */
    public String describe() {
        StringBuilder report = new StringBuilder();
        report.append(allPassed()
                ? "all " + results.size() + " versions passed"
                : failures().size() + " of " + results.size() + " versions failed");

        for (VersionOutcome outcome : results) {
            report.append("\n  ")
                    .append(outcome.passed() ? "PASS  " : "FAIL  ")
                    .append(String.format("%-10s", outcome.versionId()))
                    .append(outcome.summary());
        }
        return report.toString();
    }

    /**
     * One version's outcome.
     *
     * @param versionId the id from versions.yaml
     * @param passed    whether the scenario passed there
     * @param summary   the scenario's own summary, or why the version could not be tested
     * @param scenario  the full result, or {@code null} if the server never started
     */
    public record VersionOutcome(
            String versionId, boolean passed, String summary, ScenarioResult scenario) {

        public VersionOutcome {
            Objects.requireNonNull(versionId, "versionId");
            summary = summary == null ? "" : summary;
        }

        /**
         * A version that could not be tested at all.
         *
         * <p>Kept distinct from a scenario failure in the summary text, because "the server
         * would not start" and "the plugin misbehaved" call for completely different next
         * steps, and a matrix that blurs them sends people to debug the wrong thing.
         */
        static VersionOutcome unavailable(String versionId, String reason) {
            return new VersionOutcome(versionId, false, "could not be tested: " + reason, null);
        }
    }
}
