package io.github.xbta4224j.scanner.persistence

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.PreparedStatement
import java.time.OffsetDateTime

/**
 * Direct JDBC accessor for `corpus_entries`. We bypass JPA for this table
 * because Hibernate cannot map Postgres `vector(1536)` to a Kotlin type
 * cleanly without a custom Hibernate UserType - plumbing that overshoots
 * what Issue #19 needs.
 *
 * Cosine similarity uses pgvector's `<=>` operator (which returns 1 - cosine,
 * smaller is more similar) over the HNSW index defined in V2.
 */
@Repository
class CorpusEntryDao(
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Row(
        val id: Long,
        val symbol: String,
        val name: String,
        val description: String?,
        val canonicalAddress: String,
        val canonicalDeployer: String,
        val chain: String,
        val category: String?,
        val source: String,
        val addedAt: OffsetDateTime,
    )

    data class Match(val row: Row, val cosineSimilarity: Double)

    fun count(): Long = jdbc.queryForObject("SELECT COUNT(*) FROM corpus_entries", Long::class.java) ?: 0L

    fun countBySource(): Map<String, Long> {
        val rows = jdbc.queryForList("SELECT source, COUNT(*) AS n FROM corpus_entries GROUP BY source")
        return rows.associate { (it["source"] as String) to (it["n"] as Number).toLong() }
    }

    /** Idempotent upsert keyed on canonical_address. Re-embeds on conflict. */
    fun upsert(
        symbol: String,
        name: String,
        description: String?,
        canonicalAddress: String,
        canonicalDeployer: String,
        category: String?,
        embedding: FloatArray,
        source: String,
        addedBy: String? = null,
    ) {
        val vectorLiteral = embedding.joinToString(prefix = "[", postfix = "]", separator = ",")
        jdbc.update({ conn ->
            val ps: PreparedStatement = conn.prepareStatement(
                """
                INSERT INTO corpus_entries
                    (symbol, name, description, canonical_address, canonical_deployer,
                     chain, category, embedding, source, added_at, added_by)
                VALUES (?, ?, ?, LOWER(?), LOWER(?), 'ethereum', ?, ?::vector, ?, now(), ?)
                ON CONFLICT (canonical_address) DO UPDATE SET
                    symbol = EXCLUDED.symbol,
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    canonical_deployer = EXCLUDED.canonical_deployer,
                    category = EXCLUDED.category,
                    embedding = EXCLUDED.embedding,
                    source = EXCLUDED.source,
                    added_at = now(),
                    added_by = EXCLUDED.added_by
                """.trimIndent()
            )
            ps.setString(1, symbol)
            ps.setString(2, name)
            ps.setString(3, description)
            ps.setString(4, canonicalAddress)
            ps.setString(5, canonicalDeployer)
            ps.setString(6, category)
            ps.setString(7, vectorLiteral)
            ps.setString(8, source)
            ps.setString(9, addedBy)
            ps
        })
    }

    /**
     * Top-K cosine-nearest neighbors. Returns rows + their similarity score
     * (1.0 = identical, 0.0 = orthogonal). Empty if the table is empty.
     */
    fun nearest(embedding: FloatArray, k: Int = 5): List<Match> {
        if (count() == 0L) return emptyList()
        val vectorLiteral = embedding.joinToString(prefix = "[", postfix = "]", separator = ",")
        return jdbc.query(
            """
            SELECT id, symbol, name, description, canonical_address, canonical_deployer,
                   chain, category, source, added_at,
                   1 - (embedding <=> ?::vector) AS similarity
              FROM corpus_entries
             WHERE embedding IS NOT NULL
             ORDER BY embedding <=> ?::vector
             LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                Match(
                    row = Row(
                        id = rs.getLong("id"),
                        symbol = rs.getString("symbol"),
                        name = rs.getString("name"),
                        description = rs.getString("description"),
                        canonicalAddress = rs.getString("canonical_address"),
                        canonicalDeployer = rs.getString("canonical_deployer"),
                        chain = rs.getString("chain"),
                        category = rs.getString("category"),
                        source = rs.getString("source"),
                        addedAt = rs.getObject("added_at", OffsetDateTime::class.java),
                    ),
                    cosineSimilarity = rs.getDouble("similarity"),
                )
            },
            vectorLiteral, vectorLiteral, k,
        )
    }
}
