package com.mju.capstone_backend.global.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.test.StepVerifier;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(locations = "file:.env")
@DisplayName("데이터베이스 연결 설정 테스트")
class DatabaseConfigTest {

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private TransactionalOperator transactionalOperator;

    @Test
    @DisplayName("PostgreSQL R2DBC 연결 확인")
    void postgresConnectionTest() {
        StepVerifier.create(
                databaseClient.sql("SELECT 1 AS result")
                        .map(row -> row.get("result", Integer.class))
                        .one()
        )
                .assertNext(result -> Assertions.assertEquals(1, result))
                .verifyComplete();
    }

    @Test
    @DisplayName("TransactionalOperator 빈이 정상적으로 생성되었는지 확인")
    void transactionalOperatorBeanTest() {
        Assertions.assertNotNull(transactionalOperator);
    }
}
