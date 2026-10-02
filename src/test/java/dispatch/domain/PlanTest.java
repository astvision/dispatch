package dispatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.Schemas;
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
    void optionTrimmingCountsCodePointsAndNeverSplitsAnEmoji() {
        String emoji = "🚀";
        String fortieth = "x".repeat(39) + emoji;
        String split = "x".repeat(38) + emoji + "yy";
        Plan plan = Plan.parse("""
                {"understanding":"u","findings":[],"steps":[],"risks":[],
                 "questions":[{"text":"Which?","options":["%s","%s"]}]}""".formatted(fortieth, split));

        assertEquals(List.of(fortieth, "x".repeat(38) + emoji + "…"), plan.questionItems().getFirst().options());
    }

    @Test
    void questionObjectWithoutTextOrWithNonTextOptionIsInvalid() {
        assertThrows(InvalidPlanException.class, () -> Plan.parse(
                "{\"understanding\":\"u\",\"findings\":[],\"steps\":[],\"risks\":[],\"questions\":[{\"options\":[\"a\"]}]}"));
        assertThrows(InvalidPlanException.class, () -> Plan.parse(
                "{\"understanding\":\"u\",\"findings\":[],\"steps\":[],\"risks\":[],\"questions\":[{\"text\":\"q\",\"options\":[1]}]}"));
    }

    @Test
    void decisionsRoundTripAndAPlanWithoutThemHasNone() {
        Plan plan = new Plan("u", List.of(), List.of("s"), List.of(), List.of(),
                List.of(new PlanDecision("Which parent?", "the direct parent", List.of("the ministry"))));

        assertEquals(plan, Plan.parse(plan.toJson()));
        assertEquals(List.of(), Plan.parse("{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[]}")
                .decisions(), "a plan stored before decisions existed");
    }

    @Test
    void aDecisionKeepsThreeAlternativesAtMostCutToButtonLength() {
        Plan plan = Plan.parse("""
                {"understanding":"u","findings":[],"steps":["s"],"risks":[],"questions":[],
                 "decisions":[{"text":"Where?","chosen":"%s","alternatives":["a","b","c","d"]}]}""".formatted("x".repeat(60)));

        PlanDecision decision = plan.decisions().getFirst();
        assertEquals(List.of("a", "b", "c"), decision.alternatives());
        assertEquals(Plan.MAX_OPTION_LENGTH, decision.chosen().length());
    }

    @Test
    void aDecisionWithoutTextChosenOrAlternativesIsInvalid() {
        for (String decision : List.of("{\"chosen\":\"c\",\"alternatives\":[\"a\"]}", "{\"text\":\"t\",\"alternatives\":[\"a\"]}",
                "{\"text\":\"t\",\"chosen\":\"c\",\"alternatives\":[]}")) {
            assertThrows(InvalidPlanException.class, () -> Plan.parse(
                    "{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[],\"decisions\":[" + decision + "]}"),
                    decision);
        }
    }

    @Test
    void picksKeepListedPluginsInTheirListedFormAndDropTheRest() {
        Plan plan = Plan.parse("""
                {"understanding":"u","findings":[],"steps":["s"],"risks":[],"questions":[],"decisions":[],
                 "plugins":[" Frontend-Design ","playwright@claude-plugins-official","telegram","frontend-design",7]}""");

        assertEquals(List.of("frontend-design", "playwright"), plan.plugins());
    }

    @Test
    void aPlanStoredBeforePicksHasNone() {
        Plan plan = Plan.parse("{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[]}");

        assertEquals(List.of(), plan.plugins());
    }

    @Test
    void picksThatAreNotAnArrayAreInvalid() {
        assertThrows(InvalidPlanException.class, () -> Plan.parse(
                "{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[],\"plugins\":\"playwright\"}"));
    }

    @Test
    void picksSurviveTheStoredJson() {
        Plan plan = new Plan("u", List.of(), List.of("s"), List.of(), List.of(), List.of(), List.of("playwright"));

        assertEquals(plan, Plan.parse(plan.toJson()));
    }

    @Test
    void theSchemaRequiresPicksSoStrictOutputModesAcceptIt() {
        JsonNode schema = Json.read(Schemas.PLAN);

        assertTrue(schema.path("required").toString().contains("\"plugins\""), schema.toString());
        assertEquals("array", schema.path("properties").path("plugins").path("type").asText());
    }

    @Test
    void aPluginWhoseServerNeedsSignInIsNotOnTheList() {
        assertTrue(CuratedPlugins.match("context7").isEmpty(), "its server asks for OAuth, which nobody answers in a run");
        assertEquals(List.of(), Plan.parse("{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],"
                + "\"questions\":[],\"plugins\":[\"context7\"]}").plugins());
    }

    @Test
    void anAnswerNeedsNoStepsAndKeepsItsMarkdown() {
        Plan plan = Plan.parse("""
                {"understanding":"u","findings":[],"steps":[],"risks":[],"questions":[],"decisions":[],"plugins":[],
                 "result":"answer","answer":"**Plan.parse** in `Plan.java:45`"}""");

        assertEquals(Plan.Result.ANSWER, plan.result());
        assertEquals("**Plan.parse** in `Plan.java:45`", plan.answer());
    }

    @Test
    void anAnswerWithoutTextIsInvalid() {
        assertThrows(InvalidPlanException.class, () -> Plan.parse(
                "{\"understanding\":\"u\",\"findings\":[],\"steps\":[],\"risks\":[],\"questions\":[],\"result\":\"answer\",\"answer\":\" \"}"));
    }

    @Test
    void anUnknownResultIsInvalid() {
        assertThrows(InvalidPlanException.class, () -> Plan.parse(
                "{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[],\"result\":\"report\",\"answer\":\"\"}"));
    }

    @Test
    void aStoredPlanWithoutResultIsAPlanAndAPlansStrayAnswerIsDropped() {
        String stored = "{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[]}";
        Plan stray = Plan.parse(
                "{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[],\"result\":\"plan\",\"answer\":\"x\"}");

        assertEquals(Plan.Result.PLAN, Plan.parse(stored).result());
        assertFalse(Plan.answers(stored));
        assertFalse(Plan.answers(null));
        assertEquals("", stray.answer());
    }

    @Test
    void anAnswerSurvivesTheStoredJson() {
        Plan plan = new Plan("u", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Plan.Result.ANSWER, "42");

        assertEquals(plan, Plan.parse(plan.toJson()));
        assertTrue(Plan.answers(plan.toJson()));
    }

    @Test
    void theSchemaRequiresResultAndAnswer() {
        JsonNode schema = Json.read(Schemas.PLAN);

        assertTrue(schema.path("required").toString().contains("\"result\""), schema.toString());
        assertTrue(schema.path("required").toString().contains("\"answer\""), schema.toString());
        assertEquals("[\"plan\",\"answer\"]", schema.path("properties").path("result").path("enum").toString());
    }

    @Test
    void aPlansStoredJsonHasNoAnswerFieldsSoAnOlderJarStillReadsIt() {
        String json = new Plan("u", List.of(), List.of("s"), List.of(), List.of()).toJson();

        assertFalse(json.contains("\"result\""), json);
        assertFalse(json.contains("\"answer\""), json);
    }
}
