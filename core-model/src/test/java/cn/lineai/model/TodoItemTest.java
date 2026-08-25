package cn.lineai.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class TodoItemTest {

    @Test
    public void statusNormalization() {
        assertEquals(TodoItem.STATUS_PENDING, TodoItem.normalizeStatus(null));
        assertEquals(TodoItem.STATUS_PENDING, TodoItem.normalizeStatus("unknown_status"));
        assertEquals(TodoItem.STATUS_IN_PROGRESS, TodoItem.normalizeStatus("in_progress"));
        assertEquals(TodoItem.STATUS_IN_PROGRESS, TodoItem.normalizeStatus("InProgress"));
        assertEquals(TodoItem.STATUS_IN_PROGRESS, TodoItem.normalizeStatus("in-progress"));
        assertEquals(TodoItem.STATUS_COMPLETED, TodoItem.normalizeStatus("completed"));
        assertEquals(TodoItem.STATUS_COMPLETED, TodoItem.normalizeStatus("done"));
        assertEquals(TodoItem.STATUS_COMPLETED, TodoItem.normalizeStatus("finished"));
        assertEquals(TodoItem.STATUS_COMPLETED, TodoItem.normalizeStatus("complete"));
    }

    @Test
    public void constructorAndFlags() {
        TodoItem item1 = new TodoItem("Write tests", "in_progress");
        assertEquals("Write tests", item1.getContent());
        assertTrue(item1.isInProgress());
        assertFalse(item1.isCompleted());

        TodoItem item2 = new TodoItem("Fix bug", "done");
        assertEquals("Fix bug", item2.getContent());
        assertTrue(item2.isCompleted());
        assertFalse(item2.isInProgress());
    }

    @Test
    public void jsonParsing() throws Exception {
        assertNull(TodoItem.fromJson(null));

        JSONObject emptyObj = new JSONObject();
        assertNull(TodoItem.fromJson(emptyObj));

        JSONObject validObj = new JSONObject()
                .put("content", "Refactor models")
                .put("status", "done");
        TodoItem item = TodoItem.fromJson(validObj);
        assertNotNull(item);
        assertEquals("Refactor models", item.getContent());
        assertTrue(item.isCompleted());
    }
}
