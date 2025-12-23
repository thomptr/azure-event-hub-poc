package com.example.testconsumer.repository;

import com.example.testconsumer.entity.AccountTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {

    List<AccountTransaction> findByAccountNumber(String accountNumber);

    List<AccountTransaction> findByAccountAction(String accountAction);

    List<AccountTransaction> findByStatus(Integer status);

    List<AccountTransaction> findByStatusIsNull();

    List<AccountTransaction> findByStatusIsNotNull();

    List<AccountTransaction> findByReceivedAtBetween(Instant start, Instant end);

    List<AccountTransaction> findByProcessedAtBetween(Instant start, Instant end);

    List<AccountTransaction> findByFirstNameAndLastName(String firstName, String lastName);

    List<AccountTransaction> findByKafkaTopicAndKafkaPartitionAndKafkaOffset(
        String topic, Integer partition, Long offset);

    long countByAccountAction(String accountAction);

    long countByStatus(Integer status);

    long countByStatusIsNull();
}
