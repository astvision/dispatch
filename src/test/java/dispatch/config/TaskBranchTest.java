package dispatch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TaskBranchTest {

    @Test
    void aTasksBranchIsItsInstancesPrefixAndItsId() {
        assertEquals("dispatch/7", Config.taskBranch(null, 7));
        assertEquals("dispatch/7", Config.taskBranch("dispatch", 7));
        assertEquals("dispatch/team/7", Config.taskBranch("dispatch/team", 7));
    }
}
