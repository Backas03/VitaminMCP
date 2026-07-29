package moe.vitamin.minecraft.mcp.testkit;

import java.util.List;
import java.util.Objects;

/**
 * What happened when a scenario ran.
 *
 * <p>Shaped around the question actually asked after a failure: which step, and why. A boolean
 * plus a stack trace makes that a research problem — the trace points at the runner, not at the
 * scenario, and the scenario is what the reader wrote.
 *
 * @param passed whether every step succeeded
 * @param steps  one entry per step attempted, in order
 */
public record ScenarioResult(boolean passed, List<StepResult> steps) {

    public ScenarioResult {
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    }

    /** The step that failed, or {@code null} if none did. */
    public StepResult failure() {
        return steps.stream().filter(step -> !step.passed()).findFirst().orElse(null);
    }

    /**
     * A one-line summary, and for a failure the step that caused it.
     *
     * <p>Written so that the first line of a test failure is already the answer.
     */
    public String describe() {
        if (passed) {
            return "scenario passed (" + steps.size() + " steps)";
        }
        StepResult failed = failure();
        return "scenario failed at step " + failed.index() + " of " + steps.size()
                + " (" + failed.action() + "): " + failed.detail();
    }

    /**
     * One step's outcome.
     *
     * @param index    1-based position, matching how a reader counts the scenario
     * @param action   the action name, as written
     * @param passed   whether it succeeded
     * @param detail   what happened — the failure reason, or a short note on success
     * @param evidence server state captured at the failure, empty otherwise
     */
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
