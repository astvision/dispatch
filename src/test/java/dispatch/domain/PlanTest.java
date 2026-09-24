package dispatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanTest {

    @Test
    void parsesSchemaShapedJson() {
        Plan plan = Plan.parse("""
                {"understanding":"Make the auth timeout configurable","findings":["AuthClient.java:14 hard-codes 30s"],
                 "steps":["Read auth.timeout","Add a test"],"risks":[],"questions":[]}""");

        assertEquals("Make the auth timeout configurable", plan.understanding());
        assertEquals(List.of("AuthClient.java:14 hard-codes 30s"), plan.findings());
        assertEquals(List.of("Read auth.timeout", "Add a test"), plan.steps());
    }

    @Test
    void jsonRoundTripKeepsEveryField() {
        Plan plan = new Plan("u", List.of("f"), List.of("s1", "s2"), List.of("r"),
                List.of(new PlanQuestion("q?", List.of("yes", "no")), new PlanQuestion("Why?", List.of())));

        assertEquals(plan, Plan.parse(plan.toJson()));
    }

    @Test
    void missingFieldIsNamed() {
        InvalidPlanException error = assertThrows(InvalidPlanException.class,
                () -> Plan.parse("{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"questions\":[]}"));

        assertTrue(error.getMessage().contains("risks"), error.getMessage());
    }

    @Test
    void planWithoutStepsOrQuestionsIsInvalid() {
        assertThrows(InvalidPlanException.class,
                () -> Plan.parse("{\"understanding\":\"u\",\"findings\":[],\"steps\":[],\"risks\":[],\"questions\":[]}"));
    }

    @Test
    void planWithOnlyQuestionsIsValid() {
        Plan plan = Plan.parse("{\"understanding\":\"u\",\"findings\":[],\"steps\":[],\"risks\":[],\"questions\":[\"Which env?\"]}");

        assertEquals(List.of("Which env?"), plan.questions());
    }

    @Test
    void blankUnderstandingOrItemIsInvalid() {
        assertThrows(InvalidPlanException.class,
                () -> Plan.parse("{\"understanding\":\" \",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[]}"));
        assertThrows(InvalidPlanException.class,
                () -> Plan.parse("{\"understanding\":\"u\",\"findings\":[\"\"],\"steps\":[\"s\"],\"risks\":[],\"questions\":[]}"));
    }

    @Test
    void notJsonIsInvalid() {
        assertThrows(InvalidPlanException.class, () -> Plan.parse("Here is my plan: ..."));
    }

    @Test
    void questionsAreOldTextsOrObjectsWithOptions() {
        Plan plan = Plan.parse("""
                {"understanding":"u","findings":[],"steps":[],"risks":[],
                 "questions":["Old style?",{"text":"Which env?","options":["staging","prod"]}]}""");

        assertEquals(List.of("Old style?", "Which env?"), plan.questions());
        assertEquals(List.of(new PlanQuestion("Old style?", List.of()), new PlanQuestion("Which env?", List.of("staging", "prod"))),
                plan.questionItems());
    }

    @Test
    void optionsAreTrimmedToFourOfAtMostFortyCharactersAndBlankOnesDropped() {
        String long41 = "x".repeat(41);
        Plan plan = Plan.parse("""
                {"understanding":"u","findings":[],"steps":[],"risks":[],
                 "questions":[{"text":"Which?","options":["a"," ","b","%s","c","d"]}]}""".formatted(long41));

        assertEquals(List.of("a", "b", "x".repeat(39) + "…", "c"), plan.questionItems().getFirst().options());
    }

    @Test
    void questionObjectWithoutTextOrWithNonTextOptionIsInvalid() {
        assertThrows(InvalidPlanException.class, () -> Plan.parse(
                "{\"understanding\":\"u\",\"findings\":[],\"steps\":[],\"risks\":[],\"questions\":[{\"options\":[\"a\"]}]}"));
        assertThrows(InvalidPlanException.class, () -> Plan.parse(
                "{\"understanding\":\"u\",\"findings\":[],\"steps\":[],\"risks\":[],\"questions\":[{\"text\":\"q\",\"options\":[1]}]}"));
    }
}
