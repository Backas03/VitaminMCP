package moe.vitamin.minecraft.mcp.testkit;

import java.util.List;
import java.util.Objects;

/** How one scenario fared across every version. */
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

    /** A table, because the shape of the failures is the finding. */
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

    /** One version's outcome. */
    public record VersionOutcome(
            String versionId, boolean passed, String summary, ScenarioResult scenario) {

        public VersionOutcome {
            Objects.requireNonNull(versionId, "versionId");
            summary = summary == null ? "" : summary;
        }

        /** A version that could not be tested at all. */
        static VersionOutcome unavailable(String versionId, String reason) {
            return new VersionOutcome(versionId, false, "could not be tested: " + reason, null);
        }
    }
}
