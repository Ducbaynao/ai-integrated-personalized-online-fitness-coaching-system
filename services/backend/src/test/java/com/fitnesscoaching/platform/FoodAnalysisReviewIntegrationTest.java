package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.nutrition.*;
import com.fitnesscoaching.platform.modules.nutrition.FoodTextAnalysisController.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FoodAnalysisReviewIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("f06_review").withUsername("fitness_app").withPassword("testpass123");
    static { postgres.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.schemas", () -> "fitness");
        r.add("spring.flyway.default-schema", () -> "fitness");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired FoodAnalysisReviewService reviews;
    @Autowired FoodTextAnalysisService analyses;
    @Autowired NutritionCatalogService catalog;
    @Autowired NutritionBasicService nutrition;
    @Autowired TransactionTemplate transactions;
    static final LocalDate DAY = LocalDate.of(2026, 11, 23);
    static final Instant AT = Instant.parse("2026-11-23T12:00:00Z");
    static final AtomicLong FDC = new AtomicLong(800000);
    record Fixture(UUID owner, UUID draft, UUID item) {}
    record Catalog(UUID admin, NutritionCatalogService.Food food) {}

    @Test void correctionHttpPersistsUnknownAndExactReplayBeforeStaleExpiry() throws Exception {
        var f = fixture();
        var input = correction("correct", 1, 0, null, null, null, null, null);
        String first = mvc.perform(put(path(f)).with(jwt().jwt(j -> j.subject(f.owner().toString())))
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.draftRevision").value(2))
                .andExpect(jsonPath("$.items[0].quantity").isEmpty())
                .andExpect(jsonPath("$.items[0].itemRevision").value(1)).andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE fitness.food_analysis_requests SET expires_at=clock_timestamp()-INTERVAL '1 hour' WHERE id=?", f.draft());
        String replay = mvc.perform(put(path(f)).with(jwt().jwt(j -> j.subject(f.owner().toString())))
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(replay)).isEqualTo(mapper.readTree(first));
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), correction("correct", 1, 0,
                null, null, BigDecimal.ONE, "GRAM", null)), "NUTRITION_IDEMPOTENCY_CONFLICT");
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), correction("new", 2, 1,
                null, null, null, null, null)), "NUTRITION_ANALYSIS_EXPIRED");
        jdbc.update("UPDATE fitness.users SET status='SUSPENDED' WHERE id=?",f.owner());
        failure(() -> reviews.correct(f.owner(),f.draft(),f.item(),input),"NUTRITION_ACCESS_DENIED");
    }

    @Test void correctionAuthorityAndWrongItemAreOwnerScoped() throws Exception {
        var f = fixture(); var other = fixture(); var input = correction("owner", 1, 0, null, null, null, null, null);
        mvc.perform(put(path(f)).contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(path(f)).with(jwt().jwt(j -> j.subject(other.owner().toString())))
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isNotFound());
        UUID admin = user(false);
        mvc.perform(put(path(f)).with(jwt().jwt(j -> j.subject(admin.toString())))
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isForbidden());
        failure(() -> reviews.correct(f.owner(), f.draft(), other.item(), input), "NUTRITION_ITEM_NOT_FOUND");
        jdbc.update("UPDATE fitness.users SET status='SUSPENDED' WHERE id=?", f.owner());
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), input), "NUTRITION_ACCESS_DENIED");
    }

    @Test void correctionRejectsStaleDraftAndItemAndInactiveLifecycle() {
        var f = fixture();
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), correction("a", 0, 0, null,null,null,null,null)), "NUTRITION_VERSION_STALE");
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), correction("b", 1, 1, null,null,null,null,null)), "NUTRITION_VERSION_STALE");
        jdbc.update("UPDATE fitness.food_analysis_requests SET status='FAILED' WHERE id=?", f.draft());
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), correction("c", 1, 0, null,null,null,null,null)), "NUTRITION_ANALYSIS_STATE_CONFLICT");
    }

    @Test void executableDtosRejectMissingNullableFieldsAndUnrecognizedEdits() throws Exception {
        var f = fixture();
        for (String body : List.of(
                "{\"commandKey\":\"missing\",\"expectedDraftRevision\":1,\"expectedItemRevision\":0}",
                mapper.writeValueAsString(correction("unknown",1,0,null,null,null,null,null)).replace("}",",\"detectedName\":\"changed\"}"),
                mapper.writeValueAsString(correction("negative",-1,0,null,null,null,null,null)),
                mapper.writeValueAsString(correction("invalidquantity",1,0,null,null,new BigDecimal("0.0000001"),null,null)))) {
            mvc.perform(put(path(f)).with(jwt().jwt(j -> j.subject(f.owner().toString())))
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
        mvc.perform(post(previewPath(f)).with(jwt().jwt(j -> j.subject(f.owner().toString())))
                        .contentType("application/json").content(mapper.writeValueAsString(request("empty",1,List.of()))))
                .andExpect(status().isBadRequest());
        assertThat(analyses.detail(f.owner(),f.draft()).draftRevision()).isEqualTo(1);
    }

    @Test void correctionValidatesPartialSelectionAndDoesNotChangeRecognition() {
        var f = fixture(); var c = food(true); var food = c.food();
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), correction("pair",1,0,food.id(),null,null,null,null)), "VALIDATION_FAILED");
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), correction("portion",1,0,null,null,null,null,UUID.randomUUID())), "VALIDATION_FAILED");
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), correction("basis",1,0,null,null,null,"BOWL",null)), "VALIDATION_FAILED");
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), correction("zero",1,0,null,null,BigDecimal.ZERO,null,null)), "VALIDATION_FAILED");
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), correction("revision",1,0,food.id(),UUID.randomUUID(),null,null,null)), "NUTRITION_CATALOG_VERSION_CONFLICT");
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), correction("foreignportion",1,0,food.id(),food.revisionId(),null,null,UUID.randomUUID())), "NUTRITION_CATALOG_VERSION_CONFLICT");
        var result = reviews.correct(f.owner(), f.draft(), f.item(), correction("partial",1,0,
                food.id(),food.revisionId(),null,null,null));
        assertThat(result.items().getFirst().detectedName()).isEqualTo("unresolved oats");
        assertThat(result.items().getFirst().quantity()).isNull();
        assertThat(result.items().getFirst().quantityBasis()).isNull();
    }

    @Test void confirmedItemsCannotBeCorrectedOrPreviewed() {
        var f = fixture(); var c = food(true);
        select(f, c.food(), "GRAM", null, new BigDecimal("50"));
        var day = nutrition.addItem(f.owner(), DAY,
                new NutritionBasicService.ItemInput("Etc/UTC", AT, "BREAKFAST", "already eaten", null, null, "log"));
        UUID log = day.items().getFirst().id();
        jdbc.update("UPDATE fitness.food_analysis_draft_items SET confirmed_food_log_item_id=? WHERE id=?", log, f.item());
        failure(() -> reviews.correct(f.owner(), f.draft(), f.item(), correction("confirmed",2,1,null,null,null,null,null)), "NUTRITION_ANALYSIS_STATE_CONFLICT");
        failure(() -> reviews.preview(f.owner(), f.draft(), request("confirmed-preview",2,List.of(selected(f,c.food(),1,"50","GRAM",null)))), "NUTRITION_ANALYSIS_STATE_CONFLICT");
    }

    @Test void previewHttpReturnsImmutableSnapshotAndReplayAfterExpiryAndCorrection() throws Exception {
        var f = fixture(); var c = food(true); select(f,c.food(),"GRAM",null,new BigDecimal("50"));
        var input = request("preview",2,List.of(selected(f,c.food(),1,"50","GRAM",null)));
        String response = mvc.perform(post(previewPath(f)).with(jwt().jwt(j -> j.subject(f.owner().toString())))
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.totalNutrients.energyKcal").value(200))
                .andExpect(jsonPath("$.totalNutrients.proteinG").value(0))
                .andExpect(jsonPath("$.totalNutrients.carbohydrateG").isEmpty())
                .andExpect(jsonPath("$.items[0].catalogSource").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(analyses.detail(f.owner(),f.draft()).latestPreview()).isNotNull();
        reviews.correct(f.owner(),f.draft(),f.item(),correction("change",2,1,c.food().id(),c.food().revisionId(),new BigDecimal("60"),"GRAM",null));
        assertThat(analyses.detail(f.owner(),f.draft()).latestPreview()).isNull();
        jdbc.update("UPDATE fitness.food_analysis_requests SET expires_at=clock_timestamp()-INTERVAL '1 hour' WHERE id=?", f.draft());
        String replay = mvc.perform(post(previewPath(f)).with(jwt().jwt(j -> j.subject(f.owner().toString())))
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(replay)).isEqualTo(mapper.readTree(response));
        assertThat(count("food_analysis_previews", "request_id",f.draft())).isEqualTo(1);
        assertThat(count("meals","student_id",f.owner())).isZero();
        assertThat(count("daily_nutrition_log_status","student_id",f.owner())).isZero();
        failure(() -> reviews.preview(f.owner(),f.draft(),request("preview",2,List.of(selected(f,c.food(),1,"51","GRAM",null)))), "NUTRITION_IDEMPOTENCY_CONFLICT");
        failure(() -> reviews.preview(f.owner(),f.draft(),request("new-preview",3,List.of(selected(f,c.food(),2,"60","GRAM",null)))), "NUTRITION_ANALYSIS_EXPIRED");
        jdbc.update("UPDATE fitness.users SET status='SUSPENDED' WHERE id=?",f.owner());
        mvc.perform(post(previewPath(f)).with(jwt().jwt(j -> j.subject(f.owner().toString())))
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isForbidden());
    }

    @Test void previewCanonicalizesItemOrderAndEquivalentNumbersAndKeepsUnknownTotal() {
        var f = fixture(); var c = food(true); var known = food(true);
        // Second food has complete carbohydrate; the first item's unknown must still dominate the total.
        jdbc.update("UPDATE fitness.nutrition_catalog_revisions SET carbohydrate_g_per_100g=20 WHERE id=?", known.food().revisionId());
        UUID second = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.food_analysis_draft_items(id,request_id,item_order,detected_name) VALUES (?,?,2,'second')",second,f.draft());
        select(f,c.food(),"GRAM",null,new BigDecimal("50"));
        var f2 = new Fixture(f.owner(), f.draft(), second);
        reviews.correct(f.owner(),f.draft(),second,correction("second",2,0,known.food().id(),known.food().revisionId(),new BigDecimal("25"),"GRAM",null));
        var one = selected(f,c.food(),1,"50.0","GRAM",null);
        var two = selected(f2,known.food(),1,"25.00","GRAM",null);
        var first = reviews.preview(f.owner(),f.draft(),request("canonical",3,List.of(one,two)));
        var replay = reviews.preview(f.owner(),f.draft(),request("canonical",3,List.of(
                selected(f2,known.food(),1,"25","GRAM",null),selected(f,c.food(),1,"50.0000000","GRAM",null))));
        assertThat(first.created()).isTrue(); assertThat(replay.created()).isFalse();
        assertThat(replay.preview()).isEqualTo(first.preview());
        assertThat(first.preview().totalNutrients().energyKcal()).isEqualByComparingTo("300");
        assertThat(first.preview().totalNutrients().carbohydrateG()).isNull();
        assertThat(count("food_analysis_previews","request_id",f.draft())).isEqualTo(1);
    }

    @Test void previewAuthorityContextDuplicatesAndStaleSelectionAreRejected() throws Exception {
        var f = fixture(); var other = fixture(); var c = food(true); select(f,c.food(),"GRAM",null,BigDecimal.ONE);
        var item = selected(f,c.food(),1,"1","GRAM",null); var input = request("p",2,List.of(item));
        mvc.perform(post(previewPath(f)).contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(status().isUnauthorized());
        failure(() -> reviews.preview(other.owner(),f.draft(),input), "NUTRITION_ANALYSIS_NOT_FOUND");
        UUID admin = user(false);
        failure(() -> reviews.preview(admin,f.draft(),input), "NUTRITION_ACCESS_DENIED");
        failure(() -> reviews.preview(f.owner(),f.draft(),request("duplicate",2,List.of(item,item))), "VALIDATION_FAILED");
        failure(() -> reviews.preview(f.owner(),f.draft(),request("draft",1,List.of(item))), "NUTRITION_VERSION_STALE");
        failure(() -> reviews.preview(f.owner(),f.draft(),request("item",2,List.of(selected(f,c.food(),0,"1","GRAM",null)))), "NUTRITION_VERSION_STALE");
        failure(() -> reviews.preview(f.owner(),f.draft(),request("quantity",2,List.of(selected(f,c.food(),1,"2","GRAM",null)))), "NUTRITION_VERSION_STALE");
        for (var wrong : List.of(new FoodAnalysisPreviewRequest("day",2L,DAY.plusDays(1),"Etc/UTC",AT,"BREAKFAST",List.of(item)),
                new FoodAnalysisPreviewRequest("zone",2L,DAY,"UTC",AT,"BREAKFAST",List.of(item)),
                new FoodAnalysisPreviewRequest("time",2L,DAY,"Etc/UTC",AT.plusSeconds(1),"BREAKFAST",List.of(item)),
                new FoodAnalysisPreviewRequest("meal",2L,DAY,"Etc/UTC",AT,"LUNCH",List.of(item))))
            failure(() -> reviews.preview(f.owner(),f.draft(),wrong), "NUTRITION_ANALYSIS_CONTEXT_CONFLICT");
        failure(() -> reviews.preview(f.owner(),f.draft(),request("wrongitem",2,List.of(selected(other,c.food(),1,"1","GRAM",null)))), "NUTRITION_ITEM_NOT_FOUND");
        jdbc.update("UPDATE fitness.food_analysis_requests SET status='FAILED' WHERE id=?",f.draft());
        failure(() -> reviews.preview(f.owner(),f.draft(),input), "NUTRITION_ANALYSIS_STATE_CONFLICT");
    }

    @Test void previewRejectsStaleCatalogAndHidesLatestButReplaysHistoricalSnapshot() {
        var f = fixture(); var c = food(true); select(f,c.food(),"GRAM",null,BigDecimal.ONE);
        var input = request("catalog",2,List.of(selected(f,c.food(),1,"1","GRAM",null)));
        var preview = reviews.preview(f.owner(),f.draft(),input).preview();
        catalog.revise(c.admin(),c.food().id(),c.food().aggregateVersion(), revision(c.food().fdcId()),"revise");
        failure(() -> reviews.preview(f.owner(),f.draft(),request("stale",2,input.selectedItems())), "NUTRITION_CATALOG_VERSION_CONFLICT");
        assertThat(analyses.detail(f.owner(),f.draft()).latestPreview()).isNull();
        assertThat(reviews.preview(f.owner(),f.draft(),input).preview()).isEqualTo(preview);
    }

    @Test void previewAndCorrectionRejectUnpublishedOrRetiredFood() {
        var f = fixture(); var c = food(false);
        failure(() -> reviews.correct(f.owner(),f.draft(),f.item(),correction("unpublished",1,0,c.food().id(),c.food().revisionId(),BigDecimal.ONE,"GRAM",null)), "NUTRITION_CATALOG_NOT_FOUND");
        var published = catalog.transition(c.admin(),c.food().id(),0,"PUBLISH","test source","publish");
        select(f,published,"GRAM",null,BigDecimal.ONE);
        catalog.transition(c.admin(),published.id(),published.aggregateVersion(),"RETIRE","test retire","retire");
        failure(() -> reviews.preview(f.owner(),f.draft(),request("retired",2,List.of(selected(f,published,1,"1","GRAM",null)))), "NUTRITION_CATALOG_NOT_FOUND");
    }

    @Test void servingAndMillilitreUseEvidenceAndMissingConversionNeverGuesses() {
        var c = food(true); var food = c.food();
        for (String basis : List.of("SERVING","MILLILITRE")) {
            var f = fixture(); UUID portion = food.portions().getFirst().id();
            select(f,food,basis,portion,new BigDecimal("2"));
            var result = reviews.preview(f.owner(),f.draft(),request(basis,2,List.of(selected(f,food,1,"2",basis,portion)))).preview();
            assertThat(result.totalNutrients().energyKcal()).isEqualByComparingTo("SERVING".equals(basis) ? "320" : "12");
        }
        var f = fixture(); select(f,food,"SERVING",null,BigDecimal.ONE);
        failure(() -> reviews.preview(f.owner(),f.draft(),request("missing",2,List.of(selected(f,food,1,"1","SERVING",null)))), "NUTRITION_CONVERSION_UNKNOWN");
        for (String basis : List.of("SERVING","MILLILITRE")) {
            var unresolved = fixture(); UUID portion = food.portions().get(1).id();
            select(unresolved,food,basis,portion,BigDecimal.ONE);
            failure(() -> reviews.preview(unresolved.owner(),unresolved.draft(),request("no-factor",2,
                    List.of(selected(unresolved,food,1,"1",basis,portion)))), "NUTRITION_CONVERSION_UNKNOWN");
        }
        // Portion from another revision is never silently accepted, even with identical conversion numbers.
        var other = food(true);
        failure(() -> reviews.correct(f.owner(),f.draft(),f.item(),correction("foreign",2,1,food.id(),food.revisionId(),BigDecimal.ONE,"SERVING",other.food().portions().getFirst().id())), "NUTRITION_CATALOG_VERSION_CONFLICT");
    }

    @Test void correctAndPreviewLeaveExistingDayAndIntakeUntouched() {
        var f = fixture(); var c = food(true);
        var day = nutrition.addItem(f.owner(),DAY,new NutritionBasicService.ItemInput("Etc/UTC",AT,"BREAKFAST","existing meal",null,null,"existing"));
        nutrition.confirmDay(f.owner(),DAY,new NutritionBasicService.ConfirmInput("Etc/UTC",day.version(),false,"day-confirm"));
        String before = daySnapshot(f.owner());
        long meals = count("meals","student_id",f.owner());
        long items = jdbc.queryForObject("SELECT count(*) FROM fitness.food_log_items i JOIN fitness.meals m ON m.id=i.meal_id WHERE m.student_id=?",Long.class,f.owner());
        select(f,c.food(),"GRAM",null,BigDecimal.ONE);
        reviews.preview(f.owner(),f.draft(),request("no-intake",2,List.of(selected(f,c.food(),1,"1","GRAM",null))));
        assertThat(daySnapshot(f.owner())).isEqualTo(before);
        assertThat(count("meals","student_id",f.owner())).isEqualTo(meals);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.food_log_items i JOIN fitness.meals m ON m.id=i.meal_id WHERE m.student_id=?",Long.class,f.owner())).isEqualTo(items);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.food_analysis_draft_items WHERE request_id=? AND confirmed_food_log_item_id IS NOT NULL",Long.class,f.draft())).isZero();
    }

    @Test void previewLocksSerializeCorrectionAndCatalogWriterWithoutMixedSnapshot() throws Exception {
        var f = fixture(); var c = food(true); select(f,c.food(),"GRAM",null,new BigDecimal("50"));
        var snapshotReady = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var preview = executor.submit(() -> transactions.execute(tx -> {
                var result = reviews.preview(f.owner(),f.draft(),request("concurrent",2,List.of(selected(f,c.food(),1,"50","GRAM",null))));
                snapshotReady.countDown(); await(release); return result.preview();
            }));
            try {
                assertThat(snapshotReady.await(15,TimeUnit.SECONDS)).isTrue();
                var correction = executor.submit(() -> reviews.correct(f.owner(),f.draft(),f.item(),
                        correction("competing",2,1,c.food().id(),c.food().revisionId(),new BigDecimal("60"),"GRAM",null)));
                var writer = executor.submit(() -> catalog.revise(c.admin(),c.food().id(),c.food().aggregateVersion(),revision(c.food().fdcId()),"competing-catalog"));
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime()<deadline && jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)<2) Thread.sleep(10);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)).isGreaterThanOrEqualTo(2);
                release.countDown();
                assertThat(preview.get(15,TimeUnit.SECONDS).totalNutrients().energyKcal()).isEqualByComparingTo("200");
                writer.get(15,TimeUnit.SECONDS);
                // Either Correct commits before the catalog revision or rejects the now-stale selection.
                try { correction.get(15,TimeUnit.SECONDS); }
                catch (ExecutionException stale) { assertThat(stale.getCause()).isInstanceOf(NutritionFailure.class).extracting("code").isEqualTo("NUTRITION_CATALOG_VERSION_CONFLICT"); }
                assertThat(analyses.detail(f.owner(),f.draft()).latestPreview()).isNull();
                assertThat(count("food_analysis_previews","request_id",f.draft())).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }

    private Fixture fixture() {
        UUID owner=user(true), draft=UUID.randomUUID(), item=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.food_analysis_requests(id,student_id,input_type,input_text,status,command_key,
                request_fingerprint,log_date,timezone,consumed_at,meal_type,draft_revision)
                VALUES (?,?,'TEXT','oats','READY_FOR_CONFIRMATION',? ,?,?,'Etc/UTC',?,'BREAKFAST',1)
                """,draft,owner,"generate-"+draft,"0".repeat(64),DAY,Timestamp.from(AT));
        jdbc.update("INSERT INTO fitness.food_analysis_draft_items(id,request_id,item_order,detected_name) VALUES (?,?,1,'unresolved oats')",item,draft);
        return new Fixture(owner,draft,item);
    }
    private UUID user(boolean student) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) VALUES (?,?,'hash','Test','ACTIVE','vi-VN','Etc/UTC')",id,id+"@example.com");
        if(student) jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)",id);
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?",id,id,student?"STUDENT":"ADMIN");
        return id;
    }
    private Catalog food(boolean published) {
        UUID admin=user(false); long fdc=FDC.incrementAndGet();
        var draft=catalog.draft(admin,new NutritionCatalogService.DraftInput(fdc,"Foundation 2026",DAY,revision(fdc),"draft"));
        return new Catalog(admin,published?catalog.transition(admin,draft.id(),0,"PUBLISH","test source","publish"):draft);
    }
    private NutritionCatalogService.RevisionInput revision(long fdc) {
        return new NutritionCatalogService.RevisionInput("Test oats",new BigDecimal("400"),BigDecimal.ZERO,null,null,"v1",
                "https://fdc.nal.usda.gov/food-details/"+fdc+"/nutrients",List.of(
                        new NutritionCatalogService.PortionInput("cup",new BigDecimal("40"),new BigDecimal("1.5"),"USDA portion","approved density"),
                        new NutritionCatalogService.PortionInput("unknown conversion",null,null,null,null)));
    }
    private void select(Fixture f,NutritionCatalogService.Food food,String basis,UUID portion,BigDecimal quantity) {
        reviews.correct(f.owner(),f.draft(),f.item(),correction("select",1,0,food.id(),food.revisionId(),quantity,basis,portion));
    }
    private static FoodAnalysisItemCorrectionRequest correction(String key,long draft,long item,UUID food,UUID revision,BigDecimal q,String basis,UUID portion) {
        return new FoodAnalysisItemCorrectionRequest(key,draft,item,food,revision,q,basis,portion);
    }
    private static FoodAnalysisSelectedItem selected(Fixture f,NutritionCatalogService.Food food,long revision,String q,String basis,UUID portion) {
        return new FoodAnalysisSelectedItem(f.item(),revision,food.id(),food.revisionId(),new BigDecimal(q),basis,portion);
    }
    private static FoodAnalysisPreviewRequest request(String key,long revision,List<FoodAnalysisSelectedItem> items) {
        return new FoodAnalysisPreviewRequest(key,revision,DAY,"Etc/UTC",AT,"BREAKFAST",items);
    }
    private static String path(Fixture f) { return "/api/v1/nutrition/food-analyses/"+f.draft()+"/items/"+f.item(); }
    private static String previewPath(Fixture f) { return "/api/v1/nutrition/food-analyses/"+f.draft()+"/previews"; }
    private static void failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code) {
        assertThatThrownBy(action).isInstanceOf(NutritionFailure.class).extracting("code").isEqualTo(code);
    }
    private long count(String table,String key,UUID id) { return jdbc.queryForObject("SELECT count(*) FROM fitness."+table+" WHERE "+key+"=?",Long.class,id); }
    private String daySnapshot(UUID owner) { return jdbc.queryForObject("SELECT row_to_json(d)::text FROM fitness.daily_nutrition_log_status d WHERE student_id=? AND log_date=?",String.class,owner,DAY); }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(20,TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
