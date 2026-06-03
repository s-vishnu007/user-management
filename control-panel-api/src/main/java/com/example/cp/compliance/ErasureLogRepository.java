package com.example.cp.compliance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ErasureLogRepository extends JpaRepository<ErasureLog, UUID> {

    List<ErasureLog> findBySubjectTypeAndSubjectIdOrderByRequestedAtDesc(String subjectType, UUID subjectId);
}
