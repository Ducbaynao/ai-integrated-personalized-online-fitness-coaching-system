package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class V62FoodErasureMigrationTest {
    @Test void cleanInstallKeepsOrdinaryMealAndDraftContextRequired(){ verify(false); }
    @Test void upgradeFromV61PreservesLegacyContextAndPermitsOnlyExplicitTombstones(){ verify(true); }
    private void verify(boolean upgrade){
        try(var pg=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("f06_v62").withUsername("fitness_app").withPassword("testpass123")){
            pg.start();var ds=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());var jdbc=new JdbcTemplate(ds);
            flyway(ds,upgrade?"61":"62").migrate();
            UUID owner=UUID.randomUUID(),meal=UUID.randomUUID(),draft=UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status) VALUES (?,?,'hash','Test','ACTIVE')",owner,owner+"@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)",owner);
            jdbc.update("INSERT INTO fitness.meals(id,student_id,meal_type,consumed_at,created_by) VALUES (?,?,'LUNCH','2026-11-23T12:00:00Z',?)",meal,owner,owner);
            jdbc.update("INSERT INTO fitness.food_analysis_requests(id,student_id,input_type,input_text,command_key,request_fingerprint,log_date,timezone,consumed_at,meal_type) VALUES (?,?,'TEXT','private','key',repeat('a',64),'2026-11-23','Etc/UTC','2026-11-23T12:00:00Z','LUNCH')",draft,owner);
            if(upgrade)flyway(ds,"62").migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.flyway_schema_history WHERE version='62' AND success",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT input_text FROM fitness.food_analysis_requests WHERE id=?",String.class,draft)).isEqualTo("private");
            assertThat(jdbc.queryForObject("SELECT meal_type::text FROM fitness.meals WHERE id=?",String.class,meal)).isEqualTo("LUNCH");
            assertThatThrownBy(()->jdbc.update("UPDATE fitness.meals SET consumed_at=NULL WHERE id=?",meal)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE fitness.food_analysis_requests SET log_date=NULL WHERE id=?",draft)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            UUID request=UUID.randomUUID();jdbc.update("INSERT INTO fitness.account_erasure_requests(id,user_id,command_key,status_key_hash,target_at) VALUES (?,?,?,repeat('b',64),clock_timestamp()+interval '24 hours')",request,owner,UUID.randomUUID());
            jdbc.update("UPDATE fitness.users SET status='DISABLED' WHERE id=?",owner);
            jdbc.update("UPDATE fitness.food_analysis_requests SET erased_at=clock_timestamp(),erasure_request_id=?,input_text='ERASED',log_date=NULL,timezone=NULL,consumed_at=NULL,meal_type=NULL WHERE id=?",request,draft);
            jdbc.update("UPDATE fitness.meals SET erased_at=clock_timestamp(),erasure_request_id=?,consumed_at=NULL,meal_type=NULL WHERE id=?",request,meal);
            assertThat(jdbc.queryForObject("SELECT erasure_request_id FROM fitness.meals WHERE id=?",UUID.class,meal)).isEqualTo(request);
            assertThatThrownBy(()->jdbc.update("UPDATE fitness.food_analysis_requests SET input_text='restored' WHERE id=?",draft)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE fitness.food_analysis_requests SET erased_at=NULL,erasure_request_id=NULL,input_text='restored',log_date='2026-11-23',timezone='Etc/UTC',consumed_at='2026-11-23T12:00:00Z',meal_type='LUNCH' WHERE id=?",draft)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            flyway(ds,"62").validate();
        }
    }
    private Flyway flyway(DriverManagerDataSource ds,String target){return Flyway.configure().dataSource(ds).schemas("fitness").defaultSchema("fitness")
            .locations("classpath:db/migration").target(MigrationVersion.fromVersion(target)).load();}
}
