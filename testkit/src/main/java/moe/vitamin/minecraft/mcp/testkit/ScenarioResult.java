package moe.vitamin.minecraft.mcp.testkit;

import java.util.List;
import java.util.Objects;

/** What happened when a scenario ran. */
public record ScenarioResult(boolean passed, List<StepResult> steps) {

    public ScenarioResult {
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    }

    /** The step that failed, or {@code null} if none did. */
    public StepResult failure() {
        return steps.stream().filter(step -> !step.passed()).findFirst().orElse(null);
    }

    /** A one-line summary, and for a failure the step that caused it. */
    public String describe() {
        if (passed) {
            return "scenario passed (" + steps.size() + " steps)";
        }
        StepResult failed = failure();
        return "scenario failed at step " + failed.index() + " of " + steps.size()
                + " (" + failed.action() + "): " + failed.detail();
    }

    /** One step's outcome. */
    public record StepResult(
            int index, String action, boolean passed, String detail, String evidence) {

        public StepResult {
            Objects.requireNonNull(action, "action");
            detail = detail == null ? "" : detail;
            evidence = evidence == null ? "" : evidence;
        }

        static StepResult ok(int index, String action, String detail) {
            return new StepResult(index, action, true, detail, "");
        }

        static StepResult failed(int index, String action, String detail, String evidence) {
            return new StepResult(index, action, false, detail, evidence);
        }
    }
}
