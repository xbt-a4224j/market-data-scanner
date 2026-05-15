package io.github.xbta4224j.scanner.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DeployerReputationRepository : JpaRepository<DeployerReputation, String> {
    fun findTop20ByOrderByScamConfirmationsDesc(): List<DeployerReputation>
}
