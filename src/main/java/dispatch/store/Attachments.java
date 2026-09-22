package dispatch.store;

import dispatch.domain.Attachment;
import java.util.List;

/** SQL for attachments: first a draft's, then its task's. */
public final class Attachments {

    private Attachments() {
    }

    public static void addToDraft(Tx tx, long draftId, List<Attachment> attachments) {
        for (Attachment attachment : attachments) {
            tx.insert("INSERT INTO attachment (draft_id, file_ref, name, size) VALUES (?, ?, ?, ?)",
                    draftId, attachment.fileRef(), attachment.name(), attachment.size());
        }
    }

    /** Each part of a split message gets the whole message's files, as any of them may be about it. */
    public static void copyToDraft(Tx tx, long fromDraftId, long toDraftId) {
        addToDraft(tx, toDraftId, forDraft(tx, fromDraftId));
    }

    public static void giveToTask(Tx tx, long draftId, long taskId) {
        tx.update("UPDATE attachment SET task_id = ? WHERE draft_id = ?", taskId, draftId);
    }

    public static List<Attachment> forDraft(Tx tx, long draftId) {
        return tx.list("SELECT file_ref, name, size FROM attachment WHERE draft_id = ? ORDER BY id", Attachments::map, draftId);
    }

    public static List<Attachment> forTask(Tx tx, long taskId) {
        return tx.list("SELECT file_ref, name, size FROM attachment WHERE task_id = ? ORDER BY id", Attachments::map, taskId);
    }

    private static Attachment map(Row row) throws java.sql.SQLException {
        return new Attachment(row.string("file_ref"), row.string("name"), row.longOrNull("size"));
    }
}
