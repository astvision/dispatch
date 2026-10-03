package dispatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The Java half of the phase contract: ui/src/contract/phases.test.ts asserts the pages' Phase type is the same list. */
class PhaseContractTest {

    @Test
    void thePagesAreTypedAgainstExactlyThesePhases() throws Exception {
        assertEquals(Json.MAPPER.valueToTree(Arrays.stream(Phase.values()).map(Enum::name).toList()),
                Json.read(Files.readString(Path.of("ui/src/contract/phases.json"))));
    }
}
