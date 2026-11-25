package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.nutrition.*;
import com.fitnesscoaching.platform.modules.nutrition.FoodTextAnalysisController.*;
import com.fitnesscoaching.platform.modules.nutrition.FoodAnalysisReviewService.*;
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
class FoodAnalysisConfirmationIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("f06_confirmation").withUsername("fitness_app").withPassword("testpass123");
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
    static final LocalDate DAY = LocalDate.of(2026,11,23);
    static final Instant AT = Instant.parse("2026-11-23T12:00:00Z");
    static final AtomicLong FDC = new AtomicLong(900000);
    record Fixture(UUID owner, UUID admin, UUID draft, NutritionCatalogService.Food food,
                   List<FoodAnalysisSelectedItem> items) {}

    @Test void confirmsSelectedGroupThenReusesMealAndFinishesAllItems() throws Exception {
        var f=fixture(2); var input=intent(f,"first",List.of(f.items().getFirst()));
        var response=mvc.perform(post(path(f)).with(jwt().jwt(j->j.subject(f.owner().toString())))
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.resultingDraftRevision").value(4))
                .andReturn().getResponse().getContentAsString();
        var receipt=mapper.readValue(response,FoodAnalysisConfirmationReceipt.class);
        var item=receipt.confirmedItems().getFirst();
        assertThat(item.itemRevision()).isEqualTo(2);
        assertThat(item.nutrients().energyKcal()).isEqualByComparingTo("200");
        assertThat(item.nutrients().proteinG()).isEqualByComparingTo("0");
        assertThat(item.nutrients().carbohydrateG()).isNull();
        assertThat(receipt.remainingDraftItemIds()).containsExactly(f.items().get(1).itemId());
        assertThat(analyses.detail(f.owner(),f.draft()).confirmationStatus()).isEqualTo("PARTIALLY_CONFIRMED");
        assertThat(analyses.detail(f.owner(),f.draft()).items().get(1).confirmedFoodLogItemId()).isNull();
        assertThat(analyses.detail(f.owner(),f.draft()).latestPreview()).isNull();
        assertThat(jdbc.queryForObject("SELECT receipt_id FROM fitness.nutrition_command_receipts WHERE actor_id=? AND command_key='first'",UUID.class,f.owner())).isEqualTo(receipt.receiptId());
        assertThat(jdbc.queryForObject("SELECT entry_source FROM fitness.food_log_items WHERE id=?",String.class,item.foodLogItemId())).isEqualTo("TEXT_AI");
        assertThat(jdbc.queryForObject("SELECT confirmed_by_user FROM fitness.food_log_items WHERE id=?",Boolean.class,item.foodLogItemId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT gram_weight FROM fitness.food_log_items WHERE id=?",BigDecimal.class,item.foodLogItemId())).isEqualByComparingTo("50");
        assertThat(jdbc.queryForObject("SELECT nutrition_catalog_revision_id FROM fitness.food_log_items WHERE id=?",UUID.class,item.foodLogItemId())).isEqualTo(f.food().revisionId());
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT notes FROM fitness.food_log_items WHERE id=?",String.class,item.foodLogItemId())).path("previewId").asText()).isEqualTo(input.previewId().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.food_log_item_nutrients WHERE food_log_item_id=? AND calculation_source='CATALOG'",Long.class,item.foodLogItemId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT confirmed_food_log_item_id FROM fitness.food_analysis_draft_items WHERE id=?",UUID.class,item.itemId())).isEqualTo(item.foodLogItemId());
        var second=reviews.confirm(f.owner(),f.draft(),intent(f,"second",List.of(f.items().get(1))));
        assertThat(second.confirmedItems().getFirst().mealId()).isEqualTo(item.mealId());
        assertThat(second.remainingDraftItemIds()).isEmpty();
        var detail=analyses.detail(f.owner(),f.draft());
        assertThat(detail.confirmationStatus()).isEqualTo("CONFIRMED");
        assertThat(detail.analysisStatus()).isEqualTo("READY");
        assertThat(detail.draftRevision()).isEqualTo(5);
        assertThat(meals(f)).isEqualTo(1); assertThat(logs(f)).isEqualTo(2);
        assertThat(nutrition.readMine(f.owner(),DAY,"Etc/UTC").completeness()).isEqualTo("PARTIAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE target_id=? AND action='FOOD_ANALYSIS_ITEMS_CONFIRMED'",Long.class,f.draft())).isEqualTo(2);
    }

    @Test void replayAfterExpiryRevisionAndCatalogChangeKeepsHistoricalSnapshot() throws Exception {
        var f=fixture(2); var input=intent(f,"replay",f.items());
        var first=reviews.confirm(f.owner(),f.draft(),input);
        catalog.revise(f.admin(),f.food().id(),f.food().aggregateVersion(),revision(f.food().fdcId(),"800"),"revise");
        var current=catalog.detail(f.food().id());
        catalog.transition(f.admin(),current.id(),current.aggregateVersion(),"RETIRE","test retire","retire");
        jdbc.update("UPDATE fitness.food_analysis_requests SET expires_at=clock_timestamp()-INTERVAL '1 hour' WHERE id=?",f.draft());
        var equivalent=new ArrayList<>(f.items()); Collections.reverse(equivalent);
        equivalent.replaceAll(i->new FoodAnalysisSelectedItem(i.itemId(),i.itemRevision(),i.foodId(),i.catalogRevisionId(),new BigDecimal("50.0000000"),i.quantityBasis(),i.portionId()));
        var replayInput=copy(input,"replay",input.expectedDraftRevision(),input.previewId(),input.expectedPreviewRevision(),equivalent);
        var replay=mapper.readValue(mvc.perform(post(path(f)).with(jwt().jwt(j->j.subject(f.owner().toString())))
                        .contentType("application/json").content(mapper.writeValueAsString(replayInput)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true))
                .andReturn().getResponse().getContentAsString(),FoodAnalysisConfirmationReceipt.class);
        assertThat(replay.receiptId()).isEqualTo(first.receiptId());
        assertThat(replay.confirmedItems()).isEqualTo(first.confirmedItems());
        assertThat(replay.committedAt()).isEqualTo(first.committedAt());
        assertThat(replay.resultingDraftRevision()).isEqualTo(first.resultingDraftRevision());
        assertThat(meals(f)).isEqualTo(1); assertThat(logs(f)).isEqualTo(2);
        assertThat(receipts(f)).isEqualTo(1);
    }

    @Test void currentAuthorityAndOwnershipAreCheckedForNewAndReplayedConfirm() throws Exception {
        var f=fixture(1); var input=intent(f,"authority",f.items()); UUID other=user(true), admin=user(false);
        mvc.perform(post(path(f)).contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(status().isUnauthorized());
        mvc.perform(post(path(f)).with(jwt().jwt(j->j.subject(other.toString())))
                .contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(status().isNotFound());
        mvc.perform(post(path(f)).with(jwt().jwt(j->j.subject(admin.toString())))
                .contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(status().isForbidden());
        reviews.confirm(f.owner(),f.draft(),input);
        jdbc.update("UPDATE fitness.users SET status='SUSPENDED' WHERE id=?",f.owner());
        failure(()->reviews.confirm(f.owner(),f.draft(),input),"NUTRITION_ACCESS_DENIED");
        failure(()->reviews.confirm(other,f.draft(),input),"NUTRITION_ANALYSIS_NOT_FOUND");
        assertThat(logs(f)).isEqualTo(1);
    }

    @Test void changedKeyPayloadOrActionCannotReuseReceiptOrGenerateIdentity() {
        var f=fixture(1); var input=intent(f,"confirm-key",f.items()); reviews.confirm(f.owner(),f.draft(),input);
        failure(()->reviews.confirm(f.owner(),f.draft(),copy(input,"confirm-key",input.expectedDraftRevision()+1,input.previewId(),input.expectedPreviewRevision(),f.items())),"NUTRITION_IDEMPOTENCY_CONFLICT");
        failure(()->reviews.confirm(f.owner(),f.draft(),copy(input,"confirm-key",input.expectedDraftRevision(),UUID.randomUUID(),input.expectedPreviewRevision(),f.items())),"NUTRITION_IDEMPOTENCY_CONFLICT");
        failure(()->reviews.correct(f.owner(),f.draft(),f.items().getFirst().itemId(),new FoodAnalysisItemCorrectionRequest("confirm-key",2L,1L,null,null,null,null,null)),"NUTRITION_IDEMPOTENCY_CONFLICT");
        failure(()->nutrition.createFood(f.owner(),new NutritionBasicService.FoodInput("name","cup",BigDecimal.ONE,null,null,null,null,"confirm-key")),"NUTRITION_IDEMPOTENCY_CONFLICT");
        failure(()->analyses.create(f.owner(),new CreateRequest("text",DAY,"Etc/UTC",AT,"BREAKFAST","confirm-key")),"NUTRITION_IDEMPOTENCY_CONFLICT");
        var next=fixture(1); var pending=intent(next,"pending",next.items());
        for(String key:List.of("generate-"+next.draft(),"select-0","preview-pending"))
            failure(()->reviews.confirm(next.owner(),next.draft(),copy(pending,key,pending.expectedDraftRevision(),pending.previewId(),pending.expectedPreviewRevision(),next.items())),"NUTRITION_IDEMPOTENCY_CONFLICT");
        assertThat(meals(next)).isZero();
    }

    @Test void rejectsStaleExpiredLifecycleItemAndPreviewWithoutAnyWrites() {
        var f=fixture(1); var input=intent(f,"guards",f.items());
        failure(()->reviews.confirm(f.owner(),f.draft(),copy(input,"draft-stale",1,input.previewId(),1,f.items())),"NUTRITION_VERSION_STALE");
        failure(()->reviews.confirm(f.owner(),f.draft(),copy(input,"preview-stale",2,input.previewId(),2,f.items())),"NUTRITION_PREVIEW_STALE");
        failure(()->reviews.confirm(f.owner(),f.draft(),copy(input,"wrong-preview",2,UUID.randomUUID(),1,f.items())),"NUTRITION_PREVIEW_STALE");
        var other=fixture(1); var otherIntent=intent(other,"other",other.items());
        failure(()->reviews.confirm(f.owner(),f.draft(),copy(input,"foreign-preview",2,otherIntent.previewId(),1,f.items())),"NUTRITION_PREVIEW_STALE");
        jdbc.update("UPDATE fitness.food_analysis_draft_items SET item_revision=item_revision+1 WHERE id=?",f.items().getFirst().itemId());
        failure(()->reviews.confirm(f.owner(),f.draft(),input),"NUTRITION_VERSION_STALE");
        jdbc.update("UPDATE fitness.food_analysis_requests SET expires_at=clock_timestamp()-INTERVAL '1 hour' WHERE id=?",f.draft());
        failure(()->reviews.confirm(f.owner(),f.draft(),input),"NUTRITION_ANALYSIS_EXPIRED");
        jdbc.update("UPDATE fitness.food_analysis_requests SET status='CANCELLED',expires_at=NULL WHERE id=?",f.draft());
        failure(()->reviews.confirm(f.owner(),f.draft(),input),"NUTRITION_ANALYSIS_STATE_CONFLICT");
        assertThat(meals(f)).isZero(); assertThat(logs(f)).isZero(); assertThat(receipts(f)).isZero();
        var detail=analyses.detail(f.owner(),f.draft());
        assertThat(detail.draftRevision()).isEqualTo(2);
        assertThat(detail.items().getFirst().itemRevision()).isEqualTo(2); // the explicit stale-source fixture change
        assertThat(detail.items().getFirst().confirmedFoodLogItemId()).isNull();
    }

    @Test void previewContextFingerprintCannotBeReboundToChangedDraftContext() {
        var f=fixture(1); var input=intent(f,"pinned-context",f.items());
        jdbc.update("UPDATE fitness.food_analysis_requests SET consumed_at=? WHERE id=?",Timestamp.from(AT.plusSeconds(60)),f.draft());
        var rebound=new FoodAnalysisConfirmRequest("rebound",2L,input.previewId(),1L,DAY,"Etc/UTC",AT.plusSeconds(60),"BREAKFAST",f.items());
        failure(()->reviews.confirm(f.owner(),f.draft(),rebound),"NUTRITION_PREVIEW_STALE");
        assertEmpty(f,2);
    }

    @Test void rejectsContextDuplicatesWrongSelectionAndUnviewedGroup() {
        var f=fixture(2); var input=intent(f,"groups",f.items());
        for(var context:List.of(new FoodAnalysisConfirmRequest("day",3L,input.previewId(),1L,DAY.plusDays(1),"Etc/UTC",AT,"BREAKFAST",f.items()),
                new FoodAnalysisConfirmRequest("zone",3L,input.previewId(),1L,DAY,"UTC",AT,"BREAKFAST",f.items()),
                new FoodAnalysisConfirmRequest("time",3L,input.previewId(),1L,DAY,"Etc/UTC",AT.plusSeconds(1),"BREAKFAST",f.items()),
                new FoodAnalysisConfirmRequest("meal",3L,input.previewId(),1L,DAY,"Etc/UTC",AT,"LUNCH",f.items())))
            failure(()->reviews.confirm(f.owner(),f.draft(),context),"NUTRITION_ANALYSIS_CONTEXT_CONFLICT");
        failure(()->reviews.confirm(f.owner(),f.draft(),copy(input,"duplicate",3,input.previewId(),1,List.of(f.items().getFirst(),f.items().getFirst()))),"VALIDATION_FAILED");
        failure(()->reviews.confirm(f.owner(),f.draft(),copy(input,"subset",3,input.previewId(),1,List.of(f.items().getFirst()))),"NUTRITION_PREVIEW_STALE");
        var wrong=fixture(1);
        failure(()->reviews.confirm(f.owner(),f.draft(),copy(input,"foreign-item",3,input.previewId(),1,List.of(f.items().getFirst(),wrong.items().getFirst()))),"NUTRITION_PREVIEW_STALE");
        var i=f.items().getFirst();
        var changed=new FoodAnalysisSelectedItem(i.itemId(),i.itemRevision(),i.foodId(),i.catalogRevisionId(),BigDecimal.ONE,i.quantityBasis(),null);
        failure(()->reviews.confirm(f.owner(),f.draft(),copy(input,"quantity",3,input.previewId(),1,List.of(changed,f.items().get(1)))),"NUTRITION_PREVIEW_STALE");
        assertEmpty(f,3);
    }

    @Test void catalogRevisionOrRetirementRequiresNewStudentPreview() {
        for(boolean retired:List.of(false,true)) {
            var f=fixture(1); var input=intent(f,"catalog-guard",f.items());
            if(retired) catalog.transition(f.admin(),f.food().id(),f.food().aggregateVersion(),"RETIRE","retired","retire");
            else catalog.revise(f.admin(),f.food().id(),f.food().aggregateVersion(),revision(f.food().fdcId(),"800"),"revise");
            failure(()->reviews.confirm(f.owner(),f.draft(),input),"NUTRITION_CATALOG_VERSION_CONFLICT");
            assertEmpty(f,2);
        }
    }

    @Test void correctionInvalidatesPreviewAndPreviouslyConfirmedItemCannotBeConfirmedAgain() {
        var f=fixture(2); var input=intent(f,"before-correct",f.items());
        var item=f.items().getFirst();
        reviews.correct(f.owner(),f.draft(),item.itemId(),new FoodAnalysisItemCorrectionRequest("correction",3L,1L,item.foodId(),item.catalogRevisionId(),BigDecimal.ONE,"GRAM",null));
        failure(()->reviews.confirm(f.owner(),f.draft(),input),"NUTRITION_VERSION_STALE");
        failure(()->reviews.confirm(f.owner(),f.draft(),copy(input,"new-key",4,input.previewId(),1,f.items())),"NUTRITION_PREVIEW_STALE");
        var remaining=intent(f,"confirm-second",List.of(f.items().get(1)));
        reviews.confirm(f.owner(),f.draft(),remaining);
        failure(()->reviews.confirm(f.owner(),f.draft(),copy(remaining,"again",5,remaining.previewId(),1,remaining.selectedItems())),"NUTRITION_PREVIEW_STALE");
        assertThat(logs(f)).isEqualTo(1);
    }

    @Test void rejectsMealReuseWhenAttachedMealWasDeletedOrContextChanged() {
        for(boolean deleted:List.of(false,true)) {
            var f=fixture(2); var first=reviews.confirm(f.owner(),f.draft(),intent(f,"first",List.of(f.items().getFirst())));
            UUID meal=first.confirmedItems().getFirst().mealId();
            var second=intent(f,"second",List.of(f.items().get(1)));
            if(deleted) jdbc.update("UPDATE fitness.meals SET deleted_at=clock_timestamp() WHERE id=?",meal);
            else jdbc.update("UPDATE fitness.meals SET meal_type='DINNER' WHERE id=?",meal);
            failure(()->reviews.confirm(f.owner(),f.draft(),second),"NUTRITION_ANALYSIS_STATE_CONFLICT");
            assertThat(meals(f)).isEqualTo(1); assertThat(logs(f)).isEqualTo(1);
            assertThat(analyses.detail(f.owner(),f.draft()).draftRevision()).isEqualTo(4);
        }
    }

    @Test void rollbackWholeGroupOnInvalidSecondItem() {
        var f=fixture(2); var input=intent(f,"invalid-second",f.items());
        jdbc.update("UPDATE fitness.food_analysis_draft_items SET quantity=51 WHERE id=?",f.items().get(1).itemId());
        failure(()->reviews.confirm(f.owner(),f.draft(),input),"NUTRITION_VERSION_STALE");
        assertEmpty(f,3);
    }

    @Test void rollbackIncludesFirstItemMealMappingsNutrientsAndReceiptOnPersistenceFailure() {
        var f=fixture(2); var input=intent(f,"persist-failure",f.items());
        String function="f06_test_persist_"+f.draft().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION fitness."+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.notes::jsonb->>'analysisDraftId'='"+f.draft()+"' AND EXISTS(SELECT 1 FROM fitness.food_log_items WHERE notes::jsonb->>'analysisDraftId'='"+f.draft()+"') THEN RAISE EXCEPTION 'injected persistence failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER "+function+" BEFORE INSERT ON fitness.food_log_items FOR EACH ROW EXECUTE FUNCTION fitness."+function+"()");
        try { assertThatThrownBy(()->reviews.confirm(f.owner(),f.draft(),input)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("DROP TRIGGER "+function+" ON fitness.food_log_items"); jdbc.execute("DROP FUNCTION fitness."+function+"()"); }
        assertEmpty(f,3);
        assertThat(reviews.confirm(f.owner(),f.draft(),input).confirmedItems()).hasSize(2);
    }

    @Test void rollbackEntireGroupOnMandatoryAuditFailure() throws Exception {
        var f=fixture(2); var input=intent(f,"audit-failure",f.items());
        String function="f06_test_audit_"+f.draft().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION fitness."+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.target_id='"+f.draft()+"'::uuid AND NEW.action='FOOD_ANALYSIS_ITEMS_CONFIRMED' THEN RAISE EXCEPTION 'injected audit failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER "+function+" BEFORE INSERT ON fitness.audit_logs FOR EACH ROW EXECUTE FUNCTION fitness."+function+"()");
        try {
            String response=mvc.perform(post(path(f)).with(jwt().jwt(j->j.subject(f.owner().toString())))
                            .contentType("application/json").content(mapper.writeValueAsString(input)))
                    .andExpect(status().is5xxServerError()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("INSERT INTO","injected audit failure","SQLException");
        } finally { jdbc.execute("DROP TRIGGER "+function+" ON fitness.audit_logs"); jdbc.execute("DROP FUNCTION fitness."+function+"()"); }
        assertEmpty(f,3);
        assertThat(reviews.confirm(f.owner(),f.draft(),input).confirmedItems()).hasSize(2);
    }

    @Test void decimalRoundingAndNullNutrientsMatchViewedPreview() {
        var f=fixture(1); UUID portion=f.food().portions().getFirst().id(); var i=f.items().getFirst();
        reviews.correct(f.owner(),f.draft(),i.itemId(),new FoodAnalysisItemCorrectionRequest("tiny",2L,1L,i.foodId(),i.catalogRevisionId(),new BigDecimal("0.000001"),"MILLILITRE",portion));
        var selection=new FoodAnalysisSelectedItem(i.itemId(),2L,i.foodId(),i.catalogRevisionId(),new BigDecimal("0.000001"),"MILLILITRE",portion);
        var receipt=reviews.confirm(f.owner(),f.draft(),intent(f,"rounding",List.of(selection)));
        var log=receipt.confirmedItems().getFirst();
        assertThat(log.nutrients().energyKcal()).isEqualByComparingTo("0.000006");
        assertThat(log.nutrients().fatG()).isNull();
        assertThat(jdbc.queryForObject("SELECT gram_weight FROM fitness.food_log_items WHERE id=?",BigDecimal.class,log.foodLogItemId())).isEqualByComparingTo("0.000002");
        assertThat(jdbc.queryForObject("SELECT nutrition_catalog_portion_id FROM fitness.food_log_items WHERE id=?",UUID.class,log.foodLogItemId())).isEqualTo(portion);
    }

    @Test void numericDayOverflowReturnsValidationAndRollsBackConfirmation() throws Exception {
        var f=fixture(1);
        nutrition.addCatalogItem(f.owner(),DAY,new NutritionBasicService.CatalogItemInput("Etc/UTC",AT,"BREAKFAST",f.food().id(),f.food().revisionId(),null,"GRAM",new BigDecimal("249999999975"),"large-manual"));
        var input=intent(f,"overflow",f.items());
        mvc.perform(post(path(f)).with(jwt().jwt(j->j.subject(f.owner().toString())))
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        assertThat(meals(f)).isEqualTo(1); assertThat(logs(f)).isEqualTo(1); assertThat(receipts(f)).isZero();
        assertThat(analyses.detail(f.owner(),f.draft()).draftRevision()).isEqualTo(2);
    }

    @Test void concurrentSameIntentReturnsOneReceiptAndOneGroup() throws Exception {
        var f=fixture(2); var input=intent(f,"concurrent",f.items());
        var results=compete(f,()->reviews.confirm(f.owner(),f.draft(),input),()->reviews.confirm(f.owner(),f.draft(),input));
        var a=(FoodAnalysisConfirmationReceipt)results.get(0); var b=(FoodAnalysisConfirmationReceipt)results.get(1);
        assertThat(a.receiptId()).isEqualTo(b.receiptId()); assertThat(a.replayed()).isNotEqualTo(b.replayed());
        assertThat(logs(f)).isEqualTo(2); assertThat(meals(f)).isEqualTo(1); assertThat(receipts(f)).isEqualTo(1);
    }

    @Test void concurrentOverlappingDifferentKeysCannotDuplicateAnItem() throws Exception {
        var f=fixture(2); var input=intent(f,"one",f.items());
        var other=copy(input,"two",3,input.previewId(),1,f.items());
        var results=compete(f,()->attempt(()->reviews.confirm(f.owner(),f.draft(),input)),()->attempt(()->reviews.confirm(f.owner(),f.draft(),other)));
        assertThat(results.stream().filter(FoodAnalysisConfirmationReceipt.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(NutritionFailure.class::isInstance).count()).isEqualTo(1);
        assertThat(logs(f)).isEqualTo(2); assertThat(receipts(f)).isEqualTo(1);
    }

    @Test void concurrentDisjointGroupsRequireFreshDraftRevisionAndStillUseOneMeal() throws Exception {
        var f=fixture(2); var a=intent(f,"a",List.of(f.items().getFirst())); var b=intent(f,"b",List.of(f.items().get(1)));
        var results=compete(f,()->attempt(()->reviews.confirm(f.owner(),f.draft(),a)),()->attempt(()->reviews.confirm(f.owner(),f.draft(),b)));
        assertThat(results.stream().filter(FoodAnalysisConfirmationReceipt.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(NutritionFailure.class::isInstance).count()).isEqualTo(1);
        var remaining=analyses.detail(f.owner(),f.draft()).items().stream().filter(i->i.confirmedFoodLogItemId()==null).findFirst().orElseThrow();
        var selection=f.items().stream().filter(i->i.itemId().equals(remaining.itemId())).findFirst().orElseThrow();
        reviews.confirm(f.owner(),f.draft(),intent(f,"fresh",List.of(selection)));
        assertThat(logs(f)).isEqualTo(2); assertThat(meals(f)).isEqualTo(1);
    }

    @Test void confirmationLocksSerializeCorrectAndCatalogWriter() throws Exception {
        var f=fixture(2); var input=intent(f,"lock-confirm",List.of(f.items().getFirst()));
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(3)) {
            var confirm=executor.submit(()->transactions.execute(tx->{var result=reviews.confirm(f.owner(),f.draft(),input);entered.countDown();await(release);return result;}));
            try {
                assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();
                var correct=executor.submit(()->attempt(()->reviews.correct(f.owner(),f.draft(),f.items().get(1).itemId(),new FoodAnalysisItemCorrectionRequest("race-correct",3L,1L,null,null,null,null,null))));
                var writer=executor.submit(()->catalog.revise(f.admin(),f.food().id(),f.food().aggregateVersion(),revision(f.food().fdcId(),"800"),"race-catalog"));
                waitForLocks(2); release.countDown();
                assertThat(confirm.get(20,TimeUnit.SECONDS).confirmedItems().getFirst().nutrients().energyKcal()).isEqualByComparingTo("200");
                assertThat(correct.get(20,TimeUnit.SECONDS)).isInstanceOf(NutritionFailure.class);
                writer.get(20,TimeUnit.SECONDS);
                assertThat(logs(f)).isEqualTo(1); assertThat(receipts(f)).isEqualTo(1);
            } finally {release.countDown();}
        }
    }

    private Fixture fixture(int count) {
        UUID owner=user(true), admin=user(false), draft=UUID.randomUUID(); long fdc=FDC.incrementAndGet();
        var food=catalog.draft(admin,new NutritionCatalogService.DraftInput(fdc,"Foundation test",DAY,revision(fdc,"400"),"catalog"));
        food=catalog.transition(admin,food.id(),0,"PUBLISH","test source","publish");
        jdbc.update("""
                INSERT INTO fitness.food_analysis_requests(id,student_id,input_type,input_text,status,command_key,
                request_fingerprint,log_date,timezone,consumed_at,meal_type,draft_revision)
                VALUES (?,?,'TEXT','oats','READY_FOR_CONFIRMATION',?,?,?,'Etc/UTC',?,'BREAKFAST',1)
                """,draft,owner,"generate-"+draft,"0".repeat(64),DAY,Timestamp.from(AT));
        var items=new ArrayList<FoodAnalysisSelectedItem>();
        for(int n=0;n<count;n++) {
            UUID item=UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.food_analysis_draft_items(id,request_id,item_order,detected_name) VALUES (?,?,?,'oats')",item,draft,n+1);
            reviews.correct(owner,draft,item,new FoodAnalysisItemCorrectionRequest("select-"+n,1L+n,0L,food.id(),food.revisionId(),new BigDecimal("50"),"GRAM",null));
            items.add(new FoodAnalysisSelectedItem(item,1L,food.id(),food.revisionId(),new BigDecimal("50"),"GRAM",null));
        }
        return new Fixture(owner,admin,draft,food,List.copyOf(items));
    }
    private UUID user(boolean student) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) VALUES (?,?,'hash','Test','ACTIVE','vi-VN','Etc/UTC')",id,id+"@example.com");
        if(student)jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)",id);
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?",id,id,student?"STUDENT":"ADMIN"); return id;
    }
    private NutritionCatalogService.RevisionInput revision(long fdc,String energy) {
        return new NutritionCatalogService.RevisionInput("Test oats",new BigDecimal(energy),BigDecimal.ZERO,null,null,"v1",
                "https://fdc.nal.usda.gov/food-details/"+fdc+"/nutrients",List.of(new NutritionCatalogService.PortionInput("cup",new BigDecimal("40"),new BigDecimal("1.5"),"USDA test portion","test density")));
    }
    private FoodAnalysisConfirmRequest intent(Fixture f,String key,List<FoodAnalysisSelectedItem> items) {
        long revision=analyses.detail(f.owner(),f.draft()).draftRevision();
        var preview=reviews.preview(f.owner(),f.draft(),new FoodAnalysisPreviewRequest("preview-"+key,revision,DAY,"Etc/UTC",AT,"BREAKFAST",items)).preview();
        return new FoodAnalysisConfirmRequest(key,revision,preview.previewId(),preview.previewRevision(),DAY,"Etc/UTC",AT,"BREAKFAST",items);
    }
    private static FoodAnalysisConfirmRequest copy(FoodAnalysisConfirmRequest i,String key,long rev,UUID preview,long previewRev,List<FoodAnalysisSelectedItem> items) {
        return new FoodAnalysisConfirmRequest(key,rev,preview,previewRev,i.day(),i.timezone(),i.consumedAt(),i.mealType(),items);
    }
    private static String path(Fixture f){return "/api/v1/nutrition/food-analyses/"+f.draft()+"/confirmations";}
    private long meals(Fixture f){return jdbc.queryForObject("SELECT count(*) FROM fitness.meals WHERE student_id=?",Long.class,f.owner());}
    private long logs(Fixture f){return jdbc.queryForObject("SELECT count(*) FROM fitness.food_log_items i JOIN fitness.meals m ON m.id=i.meal_id WHERE m.student_id=?",Long.class,f.owner());}
    private long receipts(Fixture f){return jdbc.queryForObject("SELECT count(*) FROM fitness.nutrition_command_receipts WHERE actor_id=? AND command_name='FOOD_ANALYSIS_CONFIRM'",Long.class,f.owner());}
    private void assertEmpty(Fixture f,long revision) {
        assertThat(meals(f)).isZero();assertThat(logs(f)).isZero();assertThat(receipts(f)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.food_log_item_nutrients n JOIN fitness.food_log_items i ON i.id=n.food_log_item_id JOIN fitness.meals m ON m.id=i.meal_id WHERE m.student_id=?",Long.class,f.owner())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.daily_nutrition_log_status WHERE student_id=?",Long.class,f.owner())).isZero();
        var draft=analyses.detail(f.owner(),f.draft());assertThat(draft.draftRevision()).isEqualTo(revision);
        assertThat(draft.items()).allSatisfy(i->{assertThat(i.confirmedFoodLogItemId()).isNull();assertThat(i.itemRevision()).isEqualTo(1);});
    }
    private static void failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code){assertThatThrownBy(action).isInstanceOf(NutritionFailure.class).extracting("code").isEqualTo(code);}
    private static Object attempt(Callable<?> action)throws Exception{try{return action.call();}catch(NutritionFailure e){return e;}}
    private List<Object> compete(Fixture f,Callable<?> a,Callable<?> b)throws Exception {
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(3)) {
            var blocker=executor.submit(()->transactions.execute(tx->{jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,707))",rs->{while(rs.next()){}},f.owner().toString());held.countDown();await(release);return true;}));
            try{assertThat(held.await(20,TimeUnit.SECONDS)).isTrue();var first=executor.submit(a);var second=executor.submit(b);waitForLocks(2);release.countDown();blocker.get(20,TimeUnit.SECONDS);return List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));}
            finally{release.countDown();}
        }
    }
    private void waitForLocks(int count)throws InterruptedException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<end && jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)<count)Thread.sleep(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)).isGreaterThanOrEqualTo(count);
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(30,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
