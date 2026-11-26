package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.nutrition.*;
import com.fitnesscoaching.platform.modules.nutrition.FoodTextAnalysisController.*;
import com.fitnesscoaching.platform.modules.user.application.service.AccountErasureService;
import com.fitnesscoaching.platform.modules.user.application.service.AccountErasureWorker;
import com.fitnesscoaching.platform.modules.user.application.port.in.AccountErasureFence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
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
@TestPropertySource(properties={"account.erasure.worker-enabled=false","account.erasure.request-enabled=true","app.scheduling.enabled=false"})
class FoodAnalysisErasureIntegrationTest {
    static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18")
            .asCompatibleSubstituteFor("postgres")).withDatabaseName("f06_erasure").withUsername("fitness_app").withPassword("testpass123");
    static {pg.start();}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);r.add("spring.datasource.password",pg::getPassword);
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.flyway.enabled",()->"true");
        r.add("spring.flyway.schemas",()->"fitness");r.add("spring.flyway.default-schema",()->"fitness");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired FoodTextAnalysisService analyses;
    @Autowired FoodAnalysisReviewService reviews;
    @Autowired NutritionBasicService basic;
    @Autowired NutritionCatalogService catalog;
    @Autowired AccountErasureService erasure;
    @Autowired AccountErasureWorker worker;
    @Autowired NutritionAccountErasure nutritionErasure;
    @Autowired AccountErasureFence fence;
    @Autowired TransactionTemplate transactions;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    static final LocalDate DAY=LocalDate.of(2026,11,23);
    static final Instant AT=Instant.parse("2026-11-23T12:00:00Z");
    static final AtomicLong FDC=new AtomicLong(950000);
    record Fixture(UUID owner,UUID draft,List<FoodAnalysisSelectedItem> items){}

    @Test void draftPartialAndFullConfirmationCopiesAreMinimizedWithoutBreakingMappings() {
        for(int confirmed=0;confirmed<=2;confirmed++){
            var f=fixture();
            if(confirmed>0)reviews.confirm(f.owner(),f.draft(),intent(f,"confirm",f.items().subList(0,confirmed)));
            else intent(f,"unconfirmed",f.items());
            // A later Basic receipt copies confirmed F06 Food Log snapshots through readMine.
            if(confirmed>0)basic.addItem(f.owner(),DAY,new NutritionBasicService.ItemInput("Etc/UTC",AT,"SNACK","personal manual note",null,null,"manual-copy"));
            UUID request=request(f.owner());assertThat(worker.processOne()).isTrue();
            assertMinimized(f,request,confirmed);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.nutrition_command_receipts WHERE actor_id=? AND response_json<>'{}'::jsonb",Long.class,f.owner())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id=?",Long.class,f.owner())).isZero();
            assertThat(state(request)).isEqualTo("DOMAIN_REVIEW_PENDING");
        }
    }

    @Test void exclusivelyF06MealContextIsTombstonedWhileUnattributedSharedMealStaysReviewRequired(){
        var f=fixture();var receipt=reviews.confirm(f.owner(),f.draft(),intent(f,"all",f.items()));
        UUID meal=receipt.confirmedItems().getFirst().mealId();
        UUID request=request(f.owner());assertThat(worker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM fitness.meals WHERE id=?",Timestamp.class,meal)).isNull();
        assertThat(jdbc.queryForObject("SELECT erasure_request_id FROM fitness.meals WHERE id=?",UUID.class,meal)).isEqualTo(request);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.daily_nutrition_log_status WHERE student_id=?",Long.class,f.owner())).isZero();
        var shared=fixture();var second=reviews.confirm(shared.owner(),shared.draft(),intent(shared,"shared",shared.items()));
        UUID sharedMeal=second.confirmedItems().getFirst().mealId();
        jdbc.update("INSERT INTO fitness.food_log_items(meal_id,food_name_snapshot,entry_source,entry_kind,notes) VALUES (?,'counterpart name','MANUAL','DESCRIPTION_ONLY','unattributed note')",sharedMeal);
        UUID sharedRequest=request(shared.owner());assertThat(worker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM fitness.meals WHERE id=?",Timestamp.class,sharedMeal)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT notes FROM fitness.food_log_items WHERE meal_id=? AND entry_source='MANUAL'",String.class,sharedMeal)).isEqualTo("unattributed note");
        assertThat(state(sharedRequest)).isEqualTo("DOMAIN_REVIEW_PENDING");
    }

    @Test void evidencedCorrectionRedactionPreservesProcessingHistoryAndRejectsBypass(){
        var f=fixture();var receipt=reviews.confirm(f.owner(),f.draft(),intent(f,"confirm",List.of(f.items().getFirst())));
        UUID log=receipt.confirmedItems().getFirst().foodLogItemId();
        basic.correctItem(f.owner(),log,new NutritionBasicService.CorrectionInput(0,"sensitive corrected name",null,null,false,"correct-log"));
        UUID correction=jdbc.queryForObject("SELECT id FROM fitness.nutrition_item_corrections WHERE food_log_item_id=?",UUID.class,log);
        assertThatThrownBy(()->jdbc.update("UPDATE fitness.nutrition_item_corrections SET before_json='{}'::jsonb WHERE id=?",correction)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID request=request(f.owner());assertThat(worker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT before_json::text FROM fitness.nutrition_item_corrections WHERE id=?",String.class,correction)).isEqualTo("{}");
        assertThat(jdbc.queryForObject("SELECT after_json::text FROM fitness.nutrition_item_corrections WHERE id=?",String.class,correction)).isEqualTo("{}");
        assertThat(jdbc.queryForObject("SELECT to_version FROM fitness.nutrition_item_corrections WHERE id=?",Long.class,correction)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT length(prior_sha256) FROM fitness.nutrition_f06_erasure_redactions WHERE request_id=? AND correction_id=?",Integer.class,request,correction)).isEqualTo(64);
        assertThatThrownBy(()->jdbc.update("DELETE FROM fitness.nutrition_item_corrections WHERE id=?",correction)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE fitness.nutrition_f06_erasure_redactions SET prior_sha256=repeat('1',64) WHERE correction_id=?",correction)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT confirmed_food_log_item_id FROM fitness.food_analysis_draft_items WHERE id=?",UUID.class,f.items().getFirst().itemId())).isEqualTo(log);
    }

    @Test void workflowRestartIsIdempotentIsolatedAndFenced(){
        var f=fixture();var other=fixture();intent(f,"p",f.items());var otherIntent=intent(other,"p",other.items());
        UUID request=request(f.owner());assertThat(worker.processOne()).isTrue();String snapshot=snapshot(f);
        UUID stale=UUID.randomUUID();
        assertThat(erasure.minimizeCurrentScope(request,f.owner(),stale)).isNull();
        UUID claim=claim(request);
        assertThat(erasure.minimizeCurrentScope(request,f.owner(),claim)).isTrue();
        assertThat(snapshot(f)).isEqualTo(snapshot);
        assertThat(analyses.detail(other.owner(),other.draft()).items().getFirst().detectedName()).isEqualTo("sensitive detected name");
        assertThat(reviews.preview(other.owner(),other.draft(),new FoodAnalysisPreviewRequest("preview-p",3L,DAY,"Etc/UTC",AT,"BREAKFAST",otherIntent.selectedItems())).created()).isFalse();
        assertThatThrownBy(()->transactions.executeWithoutResult(tx->nutritionErasure.minimizeFoodTextAnalyses(other.owner(),request))).isInstanceOf(IllegalStateException.class);
    }

    @Test void readReplayAndRegenerationStayDeniedAfterErasureEvenIfAccountIsReactivated() throws Exception{
        var f=fixture();var input=intent(f,"saved",f.items());reviews.confirm(f.owner(),f.draft(),input);
        request(f.owner());assertThat(worker.processOne()).isTrue();
        for(String status:List.of("DISABLED","ACTIVE")){
            jdbc.update("UPDATE fitness.users SET status=?::fitness.account_status WHERE id=?",status,f.owner());
            mvc.perform(get("/api/v1/nutrition/food-analyses/"+f.draft()).with(jwt().jwt(j->j.subject(f.owner().toString())))).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/nutrition/food-analyses/"+f.draft()+"/confirmations").with(jwt().jwt(j->j.subject(f.owner().toString())))
                    .contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(status().isForbidden());
            denied(()->analyses.recover(f.owner(),"generate-"+f.draft()));
            denied(()->analyses.create(f.owner(),new CreateRequest("sensitive text",DAY,"Etc/UTC",AT,"BREAKFAST","new")));
            denied(()->basic.readMine(f.owner(),DAY,"Etc/UTC"));
        }
    }

    @Test void inFlightProviderCannotRestoreAnalysisAfterWorkerCleanup() throws Exception {
        for(int result=0;result<3;result++){
            UUID owner=user(true);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);final int mode=result;
            var service=new FoodTextAnalysisService(jdbc,context.getBean(com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery.class),
                    context.getBean(com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery.class),text->{entered.countDown();await(release);
                        if(mode==1)throw new IllegalStateException("provider unavailable");
                        return mode==2?"{}":"{\"resultType\":\"ESTIMATE\",\"items\":[{\"detectedName\":\"late sensitive name\",\"candidateNames\":[],\"quantity\":null,\"unitText\":null,\"confidence\":null,\"missingData\":[],\"limitations\":[]}],\"missingData\":[],\"limitations\":[]}";},
                    context.getBean(com.fitnesscoaching.platform.modules.audit.AuditService.class),mapper,transactions,fence);
            try(var executor=Executors.newSingleThreadExecutor()){
                var future=executor.submit(()->service.create(owner,new CreateRequest("sensitive source",DAY,"Etc/UTC",AT,"BREAKFAST","flight")));
                try{assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();UUID request=request(owner);assertThat(worker.processOne()).isTrue();
                    UUID draft=jdbc.queryForObject("SELECT id FROM fitness.food_analysis_requests WHERE student_id=?",UUID.class,owner);
                    String before=jdbc.queryForObject("SELECT to_jsonb(a)::text FROM fitness.food_analysis_requests a WHERE id=?",String.class,draft);
                    release.countDown();assertThatThrownBy(()->future.get(20,TimeUnit.SECONDS)).hasCauseInstanceOf(NutritionFailure.class);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.food_analysis_draft_items WHERE request_id=?",Long.class,draft)).isZero();
                    assertThat(jdbc.queryForObject("SELECT to_jsonb(a)::text FROM fitness.food_analysis_requests a WHERE id=?",String.class,draft)).isEqualTo(before);
                    assertThat(state(request)).isEqualTo("DOMAIN_REVIEW_PENDING");
                }finally{release.countDown();}
            }
        }
    }

    @Test void cutoffWaitsForCorrectPreviewConfirmAndReplayTransactionsThenCleanupCoversTheirCopies() throws Exception{
        for(String action:List.of("correct","preview","confirm","replay")){
            var f=fixture();var input=intent(f,"saved",f.items());
            if(action.equals("replay"))reviews.confirm(f.owner(),f.draft(),input);
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);
            try(var executor=Executors.newFixedThreadPool(2)){
                var command=executor.submit(()->transactions.execute(tx->{
                    switch(action){
                        case "correct"->reviews.correct(f.owner(),f.draft(),f.items().getFirst().itemId(),new FoodAnalysisItemCorrectionRequest("last-correct",3L,1L,null,null,null,null,null));
                        case "preview"->reviews.preview(f.owner(),f.draft(),new FoodAnalysisPreviewRequest("last-preview",3L,DAY,"Etc/UTC",AT,"BREAKFAST",f.items()));
                        default->reviews.confirm(f.owner(),f.draft(),input);
                    }
                    held.countDown();await(release);return true;}));
                try{assertThat(held.await(20,TimeUnit.SECONDS)).isTrue();var cutoff=executor.submit(()->request(f.owner()));waitForLocks(1);release.countDown();
                    command.get(20,TimeUnit.SECONDS);UUID request=cutoff.get(20,TimeUnit.SECONDS);assertThat(worker.processOne()).isTrue();
                    assertMinimized(f,request,action.equals("confirm")||action.equals("replay")?2:0);
                }finally{release.countDown();}
            }
        }
    }

    @Test void commandsWaitingBehindCutoffRecheckAuthorityAndNeverWrite() throws Exception{
        var f=fixture();var input=intent(f,"saved",f.items());var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(4)){
            var cutoff=executor.submit(()->transactions.execute(tx->{UUID id=request(f.owner());held.countDown();await(release);return id;}));
            try{assertThat(held.await(20,TimeUnit.SECONDS)).isTrue();
                var correct=executor.submit(()->attempt(()->reviews.correct(f.owner(),f.draft(),f.items().getFirst().itemId(),new FoodAnalysisItemCorrectionRequest("c",3L,1L,null,null,null,null,null))));
                var preview=executor.submit(()->attempt(()->reviews.preview(f.owner(),f.draft(),new FoodAnalysisPreviewRequest("p",3L,DAY,"Etc/UTC",AT,"BREAKFAST",f.items()))));
                var confirm=executor.submit(()->attempt(()->reviews.confirm(f.owner(),f.draft(),input)));
                waitForLocks(3);release.countDown();cutoff.get(20,TimeUnit.SECONDS);
                for(var future:List.of(correct,preview,confirm))assertThat(future.get(20,TimeUnit.SECONDS)).isInstanceOf(NutritionFailure.class).extracting("code").isEqualTo("NUTRITION_ACCESS_DENIED");
                assertThat(jdbc.queryForObject("SELECT draft_revision FROM fitness.food_analysis_requests WHERE id=?",Long.class,f.draft())).isEqualTo(3);
                assertThat(worker.processOne()).isTrue();
            }finally{release.countDown();}
        }
    }

    @Test void heldCleanupDoesNotMinimizeF06AndCanResumeWithNewLease(){
        var f=fixture();intent(f,"preview",f.items());UUID request=request(f.owner());UUID claim=claim(request);UUID approver=user(false);
        jdbc.update("INSERT INTO fitness.account_erasure_holds(request_id,case_id,legal_basis,scope,approved_by,next_review_at) VALUES (?,'test','test existing hold','ALL',?,clock_timestamp()+interval '1 day')",request,approver);
        assertThat(erasure.minimizeCurrentScope(request,f.owner(),claim)).isFalse();
        assertThat(jdbc.queryForObject("SELECT detected_name FROM fitness.food_analysis_draft_items WHERE id=?",String.class,f.items().getFirst().itemId())).isEqualTo("sensitive detected name");
        jdbc.update("UPDATE fitness.account_erasure_holds SET released_at=clock_timestamp(),released_by=? WHERE request_id=?",approver,request);
        assertThat(erasure.minimizeCurrentScope(request,f.owner(),claim(request))).isTrue();assertMinimized(f,request,0);
    }

    @Test void failedMinimizationRollsBackAndWorkerRetryCompletesOnlyCurrentScope(){
        var f=fixture();reviews.confirm(f.owner(),f.draft(),intent(f,"confirm",f.items()));UUID request=request(f.owner());
        String name="f06_fail_"+f.draft().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION fitness."+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.request_id='"+f.draft()+"'::uuid THEN RAISE EXCEPTION 'injected cleanup failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER "+name+" BEFORE UPDATE ON fitness.food_analysis_previews FOR EACH ROW EXECUTE FUNCTION fitness."+name+"()");
        try{assertThat(worker.processOne()).isTrue();assertThat(state(request)).isEqualTo("RETRYABLE");
            assertThat(jdbc.queryForObject("SELECT erased_at FROM fitness.food_analysis_requests WHERE id=?",Timestamp.class,f.draft())).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.nutrition_command_receipts WHERE actor_id=? AND response_json<>'{}'::jsonb",Long.class,f.owner())).isPositive();
        }finally{jdbc.execute("DROP TRIGGER "+name+" ON fitness.food_analysis_previews");jdbc.execute("DROP FUNCTION fitness."+name+"()");}
        jdbc.update("UPDATE fitness.account_erasure_requests SET next_attempt_at=clock_timestamp() WHERE id=?",request);
        assertThat(worker.processOne()).isTrue();assertMinimized(f,request,2);
    }

    private Fixture fixture(){
        UUID owner=user(true),admin=user(false),draft=UUID.randomUUID();long fdc=FDC.incrementAndGet();
        var food=catalog.draft(admin,new NutritionCatalogService.DraftInput(fdc,"Synthetic",DAY,new NutritionCatalogService.RevisionInput("catalog food",new BigDecimal("400"),BigDecimal.ZERO,null,null,"v1","https://fdc.nal.usda.gov/food-details/"+fdc+"/nutrients",List.of()),"catalog"));
        food=catalog.transition(admin,food.id(),0,"PUBLISH","fixture","publish");
        jdbc.update("INSERT INTO fitness.food_analysis_requests(id,student_id,input_type,input_text,status,command_key,request_fingerprint,log_date,timezone,consumed_at,meal_type,draft_revision) VALUES (?,?,'TEXT','sensitive input','READY_FOR_CONFIRMATION',?,?,?,'Etc/UTC',?,'BREAKFAST',1)",draft,owner,"generate-"+draft,"a".repeat(64),DAY,Timestamp.from(AT));
        var items=new ArrayList<FoodAnalysisSelectedItem>();
        for(int n=0;n<2;n++){
            UUID item=UUID.randomUUID();jdbc.update("INSERT INTO fitness.food_analysis_draft_items(id,request_id,item_order,detected_name,candidate_names,missing_data,limitations) VALUES (?,?,?,'sensitive detected name','[\"sensitive candidate\"]','[\"sensitive missing\"]','[\"sensitive limitation\"]')",item,draft,n+1);
            reviews.correct(owner,draft,item,new FoodAnalysisItemCorrectionRequest("select-"+n,1L+n,0L,food.id(),food.revisionId(),new BigDecimal("50"),"GRAM",null));
            items.add(new FoodAnalysisSelectedItem(item,1L,food.id(),food.revisionId(),new BigDecimal("50"),"GRAM",null));
        }
        return new Fixture(owner,draft,List.copyOf(items));
    }
    private UUID user(boolean student){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,timezone) VALUES (?,?,'hash','Test','ACTIVE','Etc/UTC')",id,id+"@example.com");
        if(student)jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)",id);
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?",id,id,student?"STUDENT":"ADMIN");return id;}
    private FoodAnalysisConfirmRequest intent(Fixture f,String key,List<FoodAnalysisSelectedItem> items){long revision=analyses.detail(f.owner(),f.draft()).draftRevision();
        var preview=reviews.preview(f.owner(),f.draft(),new FoodAnalysisPreviewRequest("preview-"+key,revision,DAY,"Etc/UTC",AT,"BREAKFAST",items)).preview();
        return new FoodAnalysisConfirmRequest(key,revision,preview.previewId(),preview.previewRevision(),DAY,"Etc/UTC",AT,"BREAKFAST",items);}
    private UUID request(UUID owner){byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);return erasure.request(owner,UUID.randomUUID(),Base64.getUrlEncoder().withoutPadding().encodeToString(key)).id();}
    private UUID claim(UUID request){UUID token=UUID.randomUUID();jdbc.update("UPDATE fitness.account_erasure_requests SET status='PROCESSING',lease_token=?,lease_expires_at=clock_timestamp()+interval '5 minutes' WHERE id=?",token,request);return token;}
    private String state(UUID request){return jdbc.queryForObject("SELECT status FROM fitness.account_erasure_requests WHERE id=?",String.class,request);}
    private String snapshot(Fixture f){return jdbc.queryForObject("SELECT to_jsonb(a)::text FROM fitness.food_analysis_requests a WHERE id=?",String.class,f.draft());}
    private void assertMinimized(Fixture f,UUID request,int mapped){
        assertThat(jdbc.queryForObject("SELECT input_text FROM fitness.food_analysis_requests WHERE id=?",String.class,f.draft())).isEqualTo("ERASED");
        assertThat(jdbc.queryForObject("SELECT erasure_request_id FROM fitness.food_analysis_requests WHERE id=?",UUID.class,f.draft())).isEqualTo(request);
        assertThat(jdbc.queryForObject("SELECT log_date FROM fitness.food_analysis_requests WHERE id=?",java.sql.Date.class,f.draft())).isNull();
        String items=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(i))::text FROM fitness.food_analysis_draft_items i WHERE request_id=?",String.class,f.draft());assertThat(items).doesNotContain("sensitive");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.food_analysis_draft_items WHERE request_id=? AND confirmed_food_log_item_id IS NOT NULL",Long.class,f.draft())).isEqualTo(mapped);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.food_analysis_previews WHERE request_id=? AND (selection_json<>'[]'::jsonb OR nutrients_json<>'{}'::jsonb)",Long.class,f.draft())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.food_log_item_nutrients n JOIN fitness.food_analysis_draft_items i ON i.confirmed_food_log_item_id=n.food_log_item_id WHERE i.request_id=?",Long.class,f.draft())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.food_log_items l JOIN fitness.food_analysis_draft_items i ON i.confirmed_food_log_item_id=l.id WHERE i.request_id=? AND (l.quantity IS NOT NULL OR l.notes IS NOT NULL OR l.food_name_snapshot<>'ERASED')",Long.class,f.draft())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.daily_nutrition_summaries WHERE student_id=?",Long.class,f.owner())).isZero();
    }
    private static void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(NutritionFailure.class).extracting("code").isEqualTo("NUTRITION_ACCESS_DENIED");}
    private static Object attempt(Callable<?> call)throws Exception{try{return call.call();}catch(NutritionFailure e){return e;}}
    private void waitForLocks(int n)throws InterruptedException{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end&&jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)<n)Thread.sleep(10);assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)).isGreaterThanOrEqualTo(n);}
    private static void await(CountDownLatch latch){try{if(!latch.await(30,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
