package io.github.xbta4224j.scanner.indexing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "deployer_reputation")
class DeployerReputation(

    @Id
    @Column(name = "address", length = 42, nullable = false)
    var address: String,

    @Column(name = "scam_confirmations", nullable = false)
    var scamConfirmations: Int = 0,

    @Column(name = "legitimate_associations", nullable = false)
    var legitimateAssociations: Int = 0,

    @Column(name = "first_seen", nullable = false)
    var firstSeen: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_updated", nullable = false)
    var lastUpdated: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "notes", columnDefinition = "text")
    var notes: String? = null,
)
