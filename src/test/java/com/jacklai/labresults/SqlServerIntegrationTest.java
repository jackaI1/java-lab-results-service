package com.jacklai.labresults;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles({"sqlserver", "demo"})
class SqlServerIntegrationTest extends ResultStorageContract {
    @Container
    static final MSSQLServerContainer<?> database = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-CU18-ubuntu-22.04")
            .acceptLicense();
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }
    @Override Object databaseId(String id) { return id; }
}
