package com.springwatch.repository;

import com.springwatch.model.entity.DlqMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DlqMessageRepository extends JpaRepository<DlqMessage, Long> {

}
