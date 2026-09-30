package dispatch.agent.sandbox;

import java.util.Optional;

/** {@code sandbox:} in the instance config and in worker.yaml. */
public enum SandboxSetting {
    AUTO,
    OFF;

    /** Empty for anything but auto or off, which the config loaders refuse; a missing setting is auto. */
    public static Optional<SandboxSetting> fromConfig(String value) {
        if (value == null || value.equals("auto")) {
            return Optional.of(AUTO);
        }
        return value.equals("off") ? Optional.of(OFF) : Optional.empty();
    }
}
