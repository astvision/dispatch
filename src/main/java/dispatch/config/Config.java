package dispatch.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One instance's validated configuration: the YAML file plus secrets from the environment. */
public record Config(
        String team,
        Path stateDir,
        Telegram telegram,
        Scheduler scheduler,
        Limits limits,
        Map<String, Agent> agents,
        List<Project> projects,
        Secrets secrets) {

    /** Instance plan limits with the project's override applied field by field. */
    public RunLimits planLimits(Project project) {
        RunLimits defaults = limits.plan();
        RunLimits override = project.limits() == null ? null : project.limits().plan();
        if (override == null) {
            return defaults;
        }
        return new RunLimits(
                override.timeout() != null ? override.timeout() : defaults.timeout(),
                override.budgetUsd() != null ? override.budgetUsd() : defaults.budgetUsd());
    }

    public record Telegram(long groupChatId, List<Member> members) {
    }

    public record Member(long id, String name) {
    }

    public record Scheduler(int maxConcurrentRuns) {
    }

    public record Limits(RunLimits plan) {
    }

    /** Either field may be null in a project override; instance limits always have both. */
    public record RunLimits(Duration timeout, BigDecimal budgetUsd) {

        private static final Pattern DURATION = Pattern.compile("(\\d+)([smh])");

        @JsonCreator
        static RunLimits fromYaml(@JsonProperty("timeout") String timeout, @JsonProperty("budgetUsd") BigDecimal budgetUsd) {
            return new RunLimits(timeout == null ? null : parseDuration(timeout), budgetUsd);
        }

        static Duration parseDuration(String text) {
            Matcher matcher = DURATION.matcher(text.strip());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("invalid duration '" + text + "' (use a number with s, m or h, e.g. 90s, 15m, 2h)");
            }
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                default -> Duration.ofHours(amount);
            };
        }
    }

    public record Agent(String command) {
    }

    public record Project(
            String name,
            String alias,
            String repo,
            String baseBranch,
            String agent,
            String model,
            List<String> copyFiles,
            Limits limits) {
    }

    public record Secrets(String telegramBotToken, String ghToken) {

        @Override
        public String toString() {
            // Keep tokens out of logs and exception messages.
            return "Secrets[telegramBotToken=***, ghToken=" + (ghToken == null ? "null" : "***") + "]";
        }
    }
}
