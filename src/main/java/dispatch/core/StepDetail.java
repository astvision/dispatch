package dispatch.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import java.util.List;

/** A step's detail as {@link dispatch.domain.RunStep} holds it: JSON the Mini App reads. */
final class StepDetail {

    private StepDetail() {
    }

    /** A test run's last lines, as the Verification keeps them. */
    static String tail(String tail) {
        return tail == null || tail.isBlank() ? null : Json.object().put("tail", tail).toString();
    }

    static String findings(List<Review.Finding> findings) {
        ObjectNode detail = Json.object();
        ArrayNode list = detail.putArray("findings");
        for (Review.Finding finding : findings) {
            list.addObject().put("severity", finding.severity()).put("file", finding.file()).put("line", finding.line())
                    .put("text", finding.text());
        }
        return detail.toString();
    }

    static String error(String error) {
        return Json.object().put("error", error).toString();
    }
}
