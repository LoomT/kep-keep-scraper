package dev.cse3000.keep

import dev.cse3000.gh.io.ScrapeContext
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class KeepDiscussionsCollector(private val ctx: ScrapeContext) {
    private val cursorKey = "keep.discussions.updated_at"
    private val log = LoggerFactory.getLogger(KeepDiscussionsCollector::class.java)

    suspend fun run(incremental: Boolean, limit: Int? = null) {
        val sinceCursor = if (incremental) ctx.cursor.get(cursorKey) else null
        var afterCursor: String? = null
        var maxUpdated: String? = null
        var processed = 0
        var totalInRepo: Int? = null
        var denominator: Any = limit ?: "?"

        outer@ while (true) {
            val vars = buildJsonObject {
                put("cursor", afterCursor?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            val resp = ctx.client.runGraphQL(LIST_DISCUSSIONS, vars).jsonObject
            checkErrors(resp, "ListDiscussions")
            val discussions = resp["data"]!!.jsonObject["repository"]!!.jsonObject["discussions"]!!.jsonObject
            if (totalInRepo == null) {
                totalInRepo = discussions["totalCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                denominator = limit ?: totalInRepo ?: "?"
                if (sinceCursor != null) {
                    log.info(
                        "Discussions phase starting (sinceCursor={}, limit={}, of {} total in repo)",
                        sinceCursor, limit, totalInRepo,
                    )
                } else {
                    log.info(
                        "Discussions phase starting (incremental={}, limit={}, total={})",
                        incremental, limit, totalInRepo,
                    )
                }
            }
            val nodes = discussions["nodes"]!!.jsonArray
            for (node in nodes) {
                if (limit != null && processed >= limit) break@outer
                val obj = node.jsonObject
                val updated = obj["updatedAt"]?.jsonPrimitive?.contentOrNull
                if (sinceCursor != null && updated != null && updated < sinceCursor) continue
                ctx.sink.emit("keep-discussions", obj, mapOf("repo" to "Kotlin/KEEP"))
                if (updated != null && updated > (maxUpdated ?: "")) maxUpdated = updated

                val number = obj["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                fetchAllComments(number)
                processed++
                if (processed % 25 == 0) {
                    log.info(
                        "Discussions progress: {}/{} processed (rate-limit remaining: {})",
                        processed, denominator, ctx.client.rateLimiter.remainingSnapshot,
                    )
                }
            }
            val pageInfo = discussions["pageInfo"]!!.jsonObject
            val hasNext = pageInfo["hasNextPage"]?.jsonPrimitive?.boolean ?: false
            if (!hasNext) break
            afterCursor = pageInfo["endCursor"]?.jsonPrimitive?.contentOrNull ?: break
        }
        maxUpdated?.let { ctx.cursor.advance(cursorKey, it) }
        log.info("Discussions phase done: {} processed, max updated_at={}", processed, maxUpdated)
    }

    private suspend fun fetchAllComments(discussionNumber: Int) {
        var afterCursor: String? = null
        while (true) {
            val vars = buildJsonObject {
                put("number", JsonPrimitive(discussionNumber))
                put("cursor", afterCursor?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            val resp = ctx.client.runGraphQL(LIST_COMMENTS, vars).jsonObject
            checkErrors(resp, "DiscussionComments(#$discussionNumber)")
            val discussion = resp["data"]!!.jsonObject["repository"]!!.jsonObject["discussion"]!!.jsonObject
            val comments = discussion["comments"]!!.jsonObject
            val nodes = comments["nodes"]!!.jsonArray
            for (node in nodes) {
                val obj = node.jsonObject
                ctx.sink.emit(
                    "keep-discussion-comments",
                    obj,
                    mapOf("repo" to "Kotlin/KEEP", "discussion" to discussionNumber.toString()),
                )
                val replyPage = obj["replies"]?.let { it as? JsonObject } ?: continue
                val replyNodes = replyPage["nodes"] as? JsonArray ?: continue
                for (reply in replyNodes) {
                    ctx.sink.emit(
                        "keep-discussion-comment-replies",
                        reply.jsonObject,
                        mapOf(
                            "repo" to "Kotlin/KEEP",
                            "discussion" to discussionNumber.toString(),
                            "parent" to (obj["id"]?.jsonPrimitive?.contentOrNull ?: ""),
                        ),
                    )
                }
                val replyHasNext = replyPage["pageInfo"]?.jsonObject
                    ?.get("hasNextPage")?.jsonPrimitive?.boolean ?: false
                if (replyHasNext) {
                    val nodeId = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    fetchAllReplies(
                        nodeId,
                        discussionNumber,
                        replyPage["pageInfo"]!!.jsonObject["endCursor"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
            val hasNext = comments["pageInfo"]!!.jsonObject["hasNextPage"]?.jsonPrimitive?.boolean ?: false
            if (!hasNext) break
            afterCursor = comments["pageInfo"]!!.jsonObject["endCursor"]?.jsonPrimitive?.contentOrNull ?: break
        }
    }

    private suspend fun fetchAllReplies(commentNodeId: String, discussionNumber: Int, startCursor: String?) {
        var afterCursor: String? = startCursor
        while (afterCursor != null) {
            val cur: String = afterCursor
            val vars = buildJsonObject {
                put("id", JsonPrimitive(commentNodeId))
                put("cursor", JsonPrimitive(cur))
            }
            val resp = ctx.client.runGraphQL(LIST_REPLIES, vars).jsonObject
            checkErrors(resp, "CommentReplies($commentNodeId)")
            val node = resp["data"]!!.jsonObject["node"]!!.jsonObject
            val replies = node["replies"]!!.jsonObject
            for (reply in replies["nodes"]!!.jsonArray) {
                ctx.sink.emit(
                    "keep-discussion-comment-replies",
                    reply.jsonObject,
                    mapOf(
                        "repo" to "Kotlin/KEEP",
                        "discussion" to discussionNumber.toString(),
                        "parent" to commentNodeId,
                    ),
                )
            }
            val hasNext = replies["pageInfo"]!!.jsonObject["hasNextPage"]?.jsonPrimitive?.boolean ?: false
            afterCursor = if (hasNext) replies["pageInfo"]!!.jsonObject["endCursor"]?.jsonPrimitive?.contentOrNull else null
        }
    }

    private fun checkErrors(resp: JsonObject, label: String) {
        val errors = resp["errors"]
        if (errors != null && errors !is JsonNull) {
            error("GraphQL errors in $label: $errors")
        }
    }

    companion object {
        private val LIST_DISCUSSIONS = $$"""
            query($cursor: String) {
              repository(owner: "Kotlin", name: "KEEP") {
                discussions(first: 50, after: $cursor, orderBy: { field: UPDATED_AT, direction: ASC }) {
                  totalCount
                  pageInfo { endCursor hasNextPage }
                  nodes {
                    id databaseId number title body bodyText url createdAt updatedAt
                    author { login url }
                    category { id name slug emoji description }
                    upvoteCount answerChosenAt
                    answer { id }
                    labels(first: 50) { nodes { name color } }
                    reactionGroups { content reactors { totalCount } }
                  }
                }
              }
            }
        """.trimIndent()

        private val LIST_COMMENTS = $$"""
            query($number: Int!, $cursor: String) {
              repository(owner: "Kotlin", name: "KEEP") {
                discussion(number: $number) {
                  comments(first: 50, after: $cursor) {
                    pageInfo { endCursor hasNextPage }
                    nodes {
                      id databaseId body bodyText url createdAt updatedAt
                      author { login }
                      isAnswer upvoteCount
                      reactionGroups { content reactors { totalCount } }
                      replies(first: 50) {
                        pageInfo { endCursor hasNextPage }
                        nodes {
                          id body bodyText createdAt updatedAt
                          author { login }
                          upvoteCount
                          reactionGroups { content reactors { totalCount } }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        private val LIST_REPLIES = $$"""
            query($id: ID!, $cursor: String) {
              node(id: $id) {
                ... on DiscussionComment {
                  replies(first: 100, after: $cursor) {
                    pageInfo { endCursor hasNextPage }
                    nodes {
                      id body bodyText createdAt updatedAt
                      author { login }
                      upvoteCount
                      reactionGroups { content reactors { totalCount } }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
