package dispatch.core;

import dispatch.Json;
import dispatch.domain.Phase;
import dispatch.domain.Plan;
import dispatch.domain.RunKind;
import dispatch.domain.RunStatus;
import dispatch.domain.Task;
import dispatch.store.PlanAnswers;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a member may see of a task and do with it, and why not (ADR 0027): the rules of ADR 0012, 0020 and 0024 in one
 * place. Every command asks before it acts, and everything that shows a task or offers an action asks too, instead of
 * working the rules out again. It reads what the rules need itself, in the caller's transaction; the conditional
 * updates stay the guard against races (ADR 0003).
 */
public final class TaskAccess {

    /** How much of a task a viewer sees (ADR 0020). */
    public enum Sight {
        FULL,
        HEADLINE,
        NONE
    }

    /** What a member may do with a task; {@link #json()} is the name payloads and the Mini App use. */
    public enum Action {
        APPROVE("approve"),
        CORRECT("correct"),
        ANSWER("answer"),
        REJECT("reject"),
        PRIORITY("priority"),
        CANCEL("cancel"),
        RETRY("retry"),
        FOLLOW_UP("followUp"),
        /** Its delivered pull request, from the result's Merge button (ADR 0007, amended). */
        MERGE("merge"),
        /** Its running execution, from the Mini App's run monitor: ⏭ skip a step, 📦 deliver now (RM-4). */
        STEER("steer");

        private final String json;

        Action(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }
    }

    /**
     * Who is looking at tasks, and which a list may hold for them: their groups' projects (ADR 0012), and their own tasks
     * wherever they are (ADR 0027). A group chat has no member and sees its own projects' tasks as headlines.
     *
     * @param ref      the member; null for a group chat
     * @param projects the projects whose tasks this viewer sees at least as a headline
     */
    public record Viewer(String ref, Set<String> projects, boolean owner) {

        public Viewer {
            projects = Set.copyOf(projects);
        }

        /** A member or a group chat: their own tasks in full, their projects' others as headlines. */
        public Viewer(String ref, Set<String> projects) {
            this(ref, projects, false);
        }

        public Sight sees(Task task) {
            if (ref != null && task.requester().ref().equals(ref)) {
                return Sight.FULL;
            }
            if (!projects.contains(task.project())) {
                return Sight.NONE;
            }
            // The machine's owner on the desktop (D-2): every task of the instance in full. ADR 0020 hides a teammate's
            // task from members, not from whoever runs the machine and could read its database.
            return owner ? Sight.FULL : Sight.HEADLINE;
        }
    }

    /**
     * One member's verdict on one task: how much of it they see, and why each action may not be taken now. An action
     * missing from {@code refusals} is allowed.
     *
     * @param task            null when there is no such task
     * @param planSeq         the plan waiting for a decision, 0 when none waits
     * @param questions       how many questions that plan asks
     * @param answered        the questions answered so far, by 1-based index
     * @param currentQuestion the first open question, 0 when none is open
     */
    public record Verdict(Task task, Sight sight, Map<Action, Refusal> refusals, int planSeq, int questions,
                          Set<Integer> answered, int currentQuestion) {

        public Verdict {
            refusals = Map.copyOf(refusals);
            answered = Set.copyOf(answered);
        }

        public Optional<Refusal> refusal(Action action) {
            return Optional.ofNullable(refusals.get(action));
        }

        public boolean allows(Action action) {
            return !refusals.containsKey(action);
        }

        /** What the member may do now, in {@link Action} order: what payloads list for the Mini App. */
        public List<Action> allowed() {
            return Arrays.stream(Action.values()).filter(this::allows).toList();
        }

        /**
         * For a tap or reply that names the plan it was shown (approve, correct, reject): a plan a newer one replaced is
         * stale. Who may act and the phase come first, as the chat has always answered them.
         */
        public Optional<Refusal> refusal(Action action, int shownPlanSeq) {
            Optional<Refusal> refused = refusal(action);
            if (refused.isPresent() && refused.get() != Refusal.OPEN_QUESTIONS) {
                return refused;
            }
            return shownPlanSeq != planSeq ? Optional.of(Refusal.STALE_PLAN) : refused;
        }

        /**
         * An answer to question {@code index} (1-based) of plan {@code shownPlanSeq}: on the plan that waits, once, and in
         * order, since answering sends the first open question and an earlier one would be sent twice.
         */
        public Optional<Refusal> answerRefusal(int shownPlanSeq, int index) {
            Optional<Refusal> refused = refusal(Action.ANSWER);
            if (refused.isPresent() && refused.get() != Refusal.ALREADY_ANSWERED) {
                return refused;
            }
            if (shownPlanSeq != planSeq || index < 1 || index > questions) {
                return Optional.of(Refusal.STALE_PLAN);
            }
            if (answered.contains(index)) {
                return Optional.of(Refusal.ALREADY_ANSWERED);
            }
            return index == currentQuestion ? Optional.empty() : Optional.of(Refusal.OUT_OF_ORDER);
        }
    }

    private final Groups groups;

    /** @param groups the running bot's groups, read on every call, so a join or a link applies at once */
    public TaskAccess(Groups groups) {
        this.groups = groups;
    }

    /**
     * A member looking at tasks: their groups' projects, and their own tasks wherever they are — but someone in no group
     * has left none of their tasks "theirs": ADR 0027 keeps a requester's own task in full only while they are in any
     * group, so a non-member's ref is dropped and they see nothing of their own past tasks.
     */
    public Viewer member(String memberRef) {
        return new Viewer(groups.isMember(memberRef) ? memberRef : null, groups.projectsOfMember(memberRef));
    }

    /** The owner on the desktop (D-2): every task of the instance's projects in full, acting as {@code memberRef}. */
    public Viewer owner(String memberRef) {
        Set<String> all = groups.all().stream().flatMap(group -> group.projects().stream()).collect(Collectors.toSet());
        // An admin in no group still acts: their verdicts allow cancelling any task (ADR 0020), and a null ref asks for none.
        boolean acts = groups.isMember(memberRef) || groups.isAdmin(memberRef);
        return new Viewer(acts ? memberRef : null, all, true);
    }

    /** A group chat looking at tasks: its own projects', as headlines. */
    public Viewer chat(String chatRef) {
        return new Viewer(null, groups.projectsOfChat(chatRef));
    }

    public Verdict of(Tx tx, String memberRef, long taskId) {
        return of(tx, memberRef, Tasks.find(tx, taskId).orElse(null));
    }

    /** @param task null when there is no such task */
    public Verdict of(Tx tx, String memberRef, Task task) {
        Sight sight = task == null ? Sight.NONE : member(memberRef).sees(task);
        boolean member = groups.isMember(memberRef);
        boolean admin = groups.isAdmin(memberRef);
        // Only a plan waiting for a decision has questions and answers that matter, and only for a member who sees it in
        // full: every lesser sight is refused before the phase rules run, and the admin-cancel rule reads neither the
        // questions nor the current question. The questions are counted, not re-validated: a plan stored before
        // questions had options holds them as plain strings.
        boolean awaiting = task != null && sight == Sight.FULL && task.phase() == Phase.AWAITING_APPROVAL;
        int planSeq = awaiting ? Runs.latestSucceededPlanSeq(tx, task.id()).orElse(0) : 0;
        int questions = awaiting && task.planJson() != null ? Json.read(task.planJson()).path("questions").size() : 0;
        Set<Integer> answered = planSeq > 0 ? PlanAnswers.of(tx, task.id(), planSeq).keySet() : Set.of();
        int current = firstOpen(questions, answered);
        Map<Action, Refusal> refusals = new EnumMap<>(Action.class);
        for (Action action : Action.values()) {
            refusal(tx, action, task, sight, member, admin, questions, current).ifPresent(refusal -> refusals.put(action, refusal));
        }
        return new Verdict(task, sight, refusals, planSeq, questions, answered, current);
    }

    /**
     * The decisions a plan offers its requester when it is sent: no Approve while it asks questions (G-1d), the same rule
     * {@link #of} applies when a button is pressed.
     */
    public static List<Action> decisions(int questions) {
        return questions == 0 ? List.of(Action.APPROVE, Action.REJECT) : List.of(Action.REJECT);
    }

    private static Optional<Refusal> refusal(Tx tx, Action action, Task task, Sight sight, boolean member, boolean admin,
                                             int questions, int current) {
        // ADR 0020: an admin may cancel any task, even one of a group they are not in and cannot see.
        boolean adminCancel = action == Action.CANCEL && admin;
        if (!member && !adminCancel) {
            return Optional.of(Refusal.NOT_MEMBER);
        }
        if (task == null || (sight == Sight.NONE && !adminCancel)) {
            return Optional.of(Refusal.NOT_FOUND);
        }
        if (sight == Sight.HEADLINE && !adminCancel) {
            return Optional.of(Refusal.NOT_REQUESTER);
        }
        return byPhase(tx, action, task, questions, current);
    }

    private static Optional<Refusal> byPhase(Tx tx, Action action, Task task, int questions, int current) {
        Phase phase = task.phase();
        return switch (action) {
            case APPROVE -> {
                if (phase != Phase.AWAITING_APPROVAL) {
                    yield Optional.of(Refusal.WRONG_PHASE);
                }
                yield decisions(questions).contains(Action.APPROVE) ? Optional.empty() : Optional.of(Refusal.OPEN_QUESTIONS);
            }
            case CORRECT, REJECT -> phase == Phase.AWAITING_APPROVAL ? Optional.empty() : Optional.of(Refusal.WRONG_PHASE);
            case ANSWER -> {
                if (phase != Phase.AWAITING_APPROVAL) {
                    yield Optional.of(Refusal.WRONG_PHASE);
                }
                yield current == 0 ? Optional.of(Refusal.ALREADY_ANSWERED) : Optional.empty();
            }
            case PRIORITY, CANCEL -> phase.isActive() ? Optional.empty() : Optional.of(Refusal.WRONG_PHASE);
            case RETRY -> {
                boolean failed = phase == Phase.FAILED
                        && Runs.latest(tx, task.id()).filter(run -> run.status() == RunStatus.FAILED).isPresent();
                yield failed ? Optional.empty() : Optional.of(Refusal.NOT_FAILED);
            }
            case FOLLOW_UP -> {
                if (phase != Phase.COMPLETED && phase != Phase.FAILED) {
                    yield Optional.of(Refusal.WRONG_PHASE);
                }
                // An answered task never executes: its reply is planned again (spec: answers).
                boolean executed = Runs.agentStartedBefore(tx, task.id(), RunKind.EXECUTE, Integer.MAX_VALUE);
                yield executed || Plan.answers(task.planJson()) ? Optional.empty() : Optional.of(Refusal.NOT_EXECUTED);
            }
            case MERGE -> mergeRefusal(task);
            case STEER -> phase == Phase.EXECUTING ? Optional.empty() : Optional.of(Refusal.WRONG_PHASE);
        };
    }

    /**
     * The Merge rule by itself, for a delivered task's result, which is written when the run completes and has no tap to
     * ask {@link #of} about: it offers Merge to its requester, who sees their own task in full, exactly when this allows it.
     * What the last run changed does not matter: a follow-up that changed nothing leaves the pull request as it was.
     */
    public static Optional<Refusal> mergeRefusal(Task task) {
        if (task.mergedAt() != null) {
            return Optional.of(Refusal.MERGED);
        }
        return task.phase() == Phase.COMPLETED && task.prUrl() != null ? Optional.empty() : Optional.of(Refusal.WRONG_PHASE);
    }

    private static int firstOpen(int questions, Set<Integer> answered) {
        for (int index = 1; index <= questions; index++) {
            if (!answered.contains(index)) {
                return index;
            }
        }
        return 0;
    }
}
