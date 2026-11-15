package io.github.x647368maker.atomicworkbench.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local report history only. Read on user request; archive once before a
 * new session clears the prior report. No timer, service or network work. */
internal class StageIssueHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("stage_issue_history_v1", Context.MODE_PRIVATE)
    fun records(): List<StageIssueHistoryPolicy.Record> {
        val array = JSONArray(prefs.getString("records", null) ?: "[]")
        require(array.length() <= StageIssueHistoryPolicy.MAX_REPORTS) { "历史报告索引异常" }
        return StageIssueHistoryPolicy.retain((0 until array.length()).map {
            val row = array.getJSONObject(it)
            StageIssueHistoryPolicy.Record(row.getString("id"),row.getLong("started"),
                row.getString("category"),row.getString("text").take(StageIssueReportPolicy.MAX_REPORT_CHARS))
        })
    }
    fun archive(record: StageIssueHistoryPolicy.Record) {
        val rows = StageIssueHistoryPolicy.retain(listOf(record) + records())
        val array = JSONArray()
        for (row in rows) array.put(JSONObject().put("id",row.id).put("started",row.startedAt)
            .put("category",row.category).put("text",row.text.take(StageIssueReportPolicy.MAX_REPORT_CHARS)))
        check(prefs.edit().putString("records",array.toString()).commit()) { "旧报告归档失败，已保留原报告" }
    }
}
